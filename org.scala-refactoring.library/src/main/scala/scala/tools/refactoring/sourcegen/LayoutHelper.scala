/*
 * Copyright 2005-2010 LAMP/EPFL
 */

package scala.tools.refactoring
package sourcegen

import scala.tools.nsc.util.SourceFile
import common.PimpedTrees
import common.Tracing

trait LayoutHelper extends CommentHelpers {
  
  self: Formatting with Tracing with PimpedTrees with common.CompilerAccess =>
  
  import global._
      
  def surroundingLayoutFromParentsAndSiblings(t: Tree) = findOriginalTree(t) map { t =>
  
    def layoutFromParent() = (originalLeftSibling(t), originalParentOf(t), originalRightSibling(t)) match {
      case (_,          None,    _          ) => layoutForCompilationUnitRoot(t)        \\ (_ => trace("compilation unit root"))
      case (None,       Some(p), None       ) => layoutForSingleChild(t, p)             \\ (_ => trace("single child with parent %s", p.getClass.getSimpleName))
      case (None,       Some(p), Some(right)) => layoutForLeftOuterChild(t, p, right)   \\ (_ => trace("left outer child with parent %s", p.getClass.getSimpleName))
      case (Some(left), Some(p), None       ) => layoutForRightOuterChild(t, p, left)   \\ (_ => trace("right outer child with parent %s", p.getClass.getSimpleName))
      case (Some(left), Some(p), Some(right)) => layoutForEnclosedChild(t, left, right, parent = p) \\ (_ => trace("enclosed child"))
    }

    val (leadingLayoutFromParent, trailingLayoutFromParent) = layoutFromParent()
    
    trace("parent leading:  %s", leadingLayoutFromParent.toString)
    trace("parent trailing: %s", trailingLayoutFromParent.toString)
    
    (leadingLayoutFromParent, trailingLayoutFromParent)
    
  } getOrElse (NoLayout, NoLayout)
  
  def leadingLayoutForTree(t: Tree): Layout = {
    findOriginalTree(t) map { t => 
      children(t) match {
        case Nil =>
          NoLayout
        case c => 
          splitLayoutBetweenParentAndFirstChild(parent = t, child = c.head)._1
      }
    } getOrElse NoLayout \\ (l => trace("leading layout for tree:  %s", l.toString))
  }
  
  def trailingLayoutForTree(t: Tree): Layout = {
    findOriginalTree(t) map { t => 
      children(t) match {
        case Nil =>
          NoLayout
        case c => 
          splitLayoutBetweenLastChildAndParent (parent = t, child = c.last)._2
      }
    } getOrElse NoLayout \\ (l => trace("trailing layout for tree:  %s", l.toString))
  }
  
  def layout(start: Int, end: Int)(implicit s: SourceFile) = Layout(s, start, end)
  def between(l: Tree, r: Tree)(implicit s: SourceFile) = layout(l.pos.end, r.pos.start)(s)

  def layoutForCompilationUnitRoot(t: Tree): (Layout, Layout) = 
    Layout(t.pos.source, 0, t.pos.start) → 
    Layout(t.pos.source, t.pos.end, t.pos.source.length)
    
  def layoutForSingleChild(t: Tree, p: Tree): (Layout, Layout) = 
    splitLayoutBetweenParentAndFirstChild(child = t, parent = p)._2 →     
    splitLayoutBetweenLastChildAndParent(child = t, parent = p)._1
    
  def layoutForLeftOuterChild(t: Tree, parent: Tree, right: Tree): (Layout, Layout) = 
    splitLayoutBetweenParentAndFirstChild(child = t, parent = parent)._2 → 
    splitLayoutBetweenSiblings(left = t, right = right, parent = parent)._1 
    
  def layoutForRightOuterChild(t: Tree, parent: Tree, left: Tree): (Layout, Layout) = 
    splitLayoutBetweenSiblings(left = left, right = t, parent = parent)._2  → 
    splitLayoutBetweenLastChildAndParent(child = t, parent = parent)._1
    
  def layoutForEnclosedChild(t: Tree, left: Tree, right: Tree, parent: Tree): (Layout, Layout) = 
    splitLayoutBetweenSiblings(parent = parent, left = left, right = t)._2 →
    splitLayoutBetweenSiblings(parent = parent, left = t, right = right)._1

  def splitLayoutBetweenParentAndFirstChild(child: Tree, parent: Tree): (Layout, Layout) = {
    
    trace("splitting layout between parent %s and first child %s", parent.getClass.getSimpleName, child.getClass.getSimpleName)
    
    implicit val currentFile = child.pos.source
    
    val (left, right) = (fixValDefPosition(parent), fixValDefPosition(child)) match {
      
      case (p: PackageDef, c) =>
        layout(p.pos.start, c.pos.start) → NoLayout
        
      case (p @ ClassDef(ModifierTree(Nil), _, _, _), c) =>
        layout(p.pos.start,       p.pos.point) → layout(p.pos.point + p.name.length, c.pos.start)
        
      case (p @ ClassDef(ModifierTree(mods), _, _, _), c) =>
        layout(p.pos.start, mods.head.pos.start) → NoLayout
        
      case (p @ ModuleDef(ModifierTree(Nil), _, _), c) =>
        layout(p.pos.start,       p.pos.point) → layout(p.pos.point + p.name.length, c.pos.start)
        
      case (p @ ModuleDef(ModifierTree(mods), _, _), c) =>
        layout(p.pos.start, mods.head.pos.start) → NoLayout
        
      case (p @ TypeDef(ModifierTree(Nil), _, _, _), c) =>
        layout(p.pos.start,       p.pos.point) → layout(p.pos.point + p.name.length, c.pos.start)
        
      case (p @ TypeDef(ModifierTree(mods), _, _, _), c) =>
        layout(p.pos.start, mods.head.pos.start) → NoLayout
        
      case (p: Template, c) =>
        layout(p.pos.start, c.pos.start) splitAfter ('{', '(')
        
      case (p: If, c) =>
        layout(p.pos.start, c.pos.start) splitAfter '('
        
      case (p: ValOrDefDef, c: ModifierTree) =>
        NoLayout → NoLayout
        
      case (p: ValOrDefDef, c) =>
        layout(p.pos.start, p.namePosition.start) → NoLayout
        
      case (p: Apply, c) =>
        layout(p.pos.start, c.pos.start) → NoLayout
        
      case (p: Select, c) =>
        NoLayout → NoLayout
        
      case (p: Block, c) =>
        val l = layout(p.pos.start, c.pos.start)
        if(l contains "{")
          l splitAfter '{'
        else
          l splitBefore('\r', '\n')
         
      case (p: Try, c: Block) =>
        layout(p.pos.start, c.pos.start) splitBefore ('{')
         
      case (p: Try, c) =>
        layout(p.pos.start, c.pos.start) splitAfter ('\n', '{')
         
      case (p: Import, c) =>
        layout(p.pos.start, p.pos.point) → NoLayout
         
      case (p: ImportSelectorTree, c) =>
        layout(p.pos.start, c.pos.start) → NoLayout
         
      case (p: New, c) =>
        layout(p.pos.start + "new".length, c.pos.start) → NoLayout
         
      case (p: Match, c) =>
        // c is the match selector
        NoLayout → layout(p.pos.start, c.pos.start) 
         
      case (p, c) =>
        layout(p.pos.start, c.pos.start) → NoLayout
    }
    
    trace("  results in «%s» and «%s»", left, right)
    
    (left, right)
  }
           
  private def fixValDefPosition(t: Tree): Tree = {
    
    t match {
      case t @ ValDef(_, _, _, rhs) =>
      
        val childBeforeRhs = children(t) takeWhile (c => !(c samePos rhs)) lastOption match {
          case Some(tree) => tree
          case None => return t
        }
      
        if(childBeforeRhs.pos.isRange && rhs.pos.isRange && between(childBeforeRhs, rhs)(t.pos.source).contains("{")) {
        
          val offsetToClosing = layout(rhs.pos.end, t.pos.source.length)(t.pos.source).asText takeWhile (_ != '}') length
          val ct = t.copy().copyAttrs(t)
        
          val end = t.pos.end + offsetToClosing + 1
          ct setPos (ct.pos withEnd end)
        } else {
          t
        }
      
      case _ => t
    }
  }

  def splitLayoutBetweenLastChildAndParent(child: Tree, parent: Tree): (Layout, Layout) = {
     
    trace("splitting layout between last child %s and parent %s", child.getClass.getSimpleName, parent.getClass.getSimpleName)
     
    implicit val currentFile = child.pos.source
     
    val (left, right) = (fixValDefPosition(child), fixValDefPosition(parent)) match {
       
      case (c: Block, p: Block) =>
        layout(c.pos.end, p.pos.end) splitBefore ('\r', '\n', '}')
        
      case (c, p: PackageDef) =>
        layout(c.pos.end, p.pos.end) splitAfter '\n'
         
      case (c, p @ (_: ClassDef | _: ModuleDef)) =>
        layout(c.pos.end, p.pos.end) splitAfter '}'
         
       case (c: SuperConstructorCall, p: Template) =>
         layout(c.pos.end, p.pos.end) splitAtAndExclude ')'
         
       case (c, p: Template) =>
         layout(c.pos.end, p.pos.end) splitBefore (')', '\r', '\n')
         
       case (c, p: If) =>
         layout(c.pos.end, p.pos.end) splitBefore (')')
    
       case (c, p: ValOrDefDef) =>
         layout(c.pos.end, p.pos.end) splitAfterLast '}'
       
       case (c, p: Select) =>
         NoLayout → NoLayout
        
       case (c, p: Block) =>
         layout(c.pos.end, p.pos.end) splitBefore ('\r', '\n')
         
       case (c, p: Match) =>
         layout(c.pos.end, p.pos.end) splitBefore ('\r', '\n')
         
       case (c, p) =>
         NoLayout → layout(c.pos.end, p.pos.end)
     }
    
     trace("  results in «%s» and «%s»", left, right)
     
     (left, right)
   }
   
  private val Else = """(?ms)(.*?)(?:\r?\n\s*\}\s*)?\r?\n?\s*else\s*(?:[\s\{]*\r?\n\s*)?(.*)""".r
  private val StartComment = """(.*?)(/\*.*)""".r
  private val Class = """(.*?)(class.*)""".r
  private val EmptyParens = """(?ms)(.*?\(\s*\)\s*)(.*)""".r
  private val OpeningBrace = """(.*?)\((.*)""".r
  private val OpeningCurlyBrace = """(?ms)(.*?)\{(.*)""".r
  private val Match = """(?ms)(.*?)\s?match(.*)""".r
  private val Colon = """(.*?:\s+)(.*)""".r
  private val Dot = """(.*)(\..*)""".r
  private val Arrow = """(?ms)(.*?=>[ ]?)(\r?\n?.*)""".r
  private val Equals = """(?ms)(.*?=\s?)(.*)""".r
  private val ClosingBrace = """(?ms)(.*?)\)(.*)""".r
  private val ClosingCurlyBrace = """(?ms)(.*?\}\s*)(\r?\n.*)""".r
  private val Comma = """(.*?),(.*)""".r
  private val NewLine = """(?ms)(.*?)(\r?\n.*)""".r
  private val ImportStatementNewline = """(?ms)(.*)(\r?\n.*?import.*)""".r
  private val ImportStatement = """(?ms)(.*)(.*?import.*)""".r
 
  def splitLayoutBetweenSiblings(parent: Tree, left: Tree, right: Tree): (Layout, Layout) = {
      
    def mergeLayoutWithComment(l: Seq[Char], c: Seq[Char]) = l zip c map {
      case (' ', _1) => _1
      case (_1, ' ') => _1
      case ('\n', '\n') => '\n'
      case ('\r', '\r') => '\r'
    } mkString
    
    def split(layout: String): (String, String, String) = {

      /* Annotations are not represented by trees, so the annotation code ends
       * up in the layout. The current workaround is simply to not split some
       * kinds of layout that contain an @. */
      def layoutDoesNotIncludeAnnotation = !layout.contains("@")
      
      (layout match {
        case Else(l, r)            => Some(l, r, "else")
        case Match(l, r)           => Some(l, r, "match")
        case StartComment(l, r)    => Some(l, r, "StartComment")
        case Class(l, r)           => Some(l, r, "Class")
        case Colon(l, r)           => Some(l, r, "Colon")
        case EmptyParens(l, r)     => Some(l, r, "EmptyParens")
        case OpeningBrace(l, r)    => Some(l, r, "OpeningBrace")
        case Arrow(l, r)       => Some(l, r, "`=>`")
        case _                     => None
      }) orElse (layout match { // Work around https://lampsvn.epfl.ch/trac/scala/ticket/1133
        case ClosingBrace(l, r) if layoutDoesNotIncludeAnnotation => Some(l, r, "ClosingBrace")
        case Equals(l, r)       if layoutDoesNotIncludeAnnotation => Some(l, r, "Equals")
        case ImportStatementNewline(l, r) => Some(l, r, "ImportStatement Newline")
        case ImportStatement(l, r) => Some(l, r, "ImportStatement")
        case ClosingCurlyBrace(l, r)=> Some(l, r, "ClosingCurlyBrace")
        case NewLine(l, r)         => Some(l, r, "NewLine")
        case Comma(l, r)           => Some(l, r, "Comma")
        case _                     => None
      }) orElse (layout match {
        case Dot(l, r)             => Some(l, r, "Dot")
        case s                     => Some(s, "", "NoMatch")
      }) get
    }
    
    (fixValDefPosition(left), fixValDefPosition(right)) match {
      case (_, EmptyTree) | (EmptyTree, _) => NoLayout → NoLayout
      
      case (l: Import, r: Import) => 
        NoLayout → NoLayout
      
      case (l, r: Import) => 
        between(l, r)(l.pos.source) → NoLayout
        
      case (l: ImportSelectorTree, r: ImportSelectorTree) =>
        NoLayout → NoLayout
        
      case (l, r: ImportSelectorTree) if !l.isInstanceOf[ImportSelectorTree] =>
        // All the layout, like '.' and '{' belongs to the selector.
        between(l, r)(l.pos.source) → NoLayout

      case (l, r) => 
        
        val source = between(l, r)(left.pos.source).toString
        val (layout, comments) = splitComment(source)
        
        val (ll, lr, rule) = (l, parent, r) match {
            
          case (l: Match, _, r) =>
            layout match {
              case ClosingBrace(l, r)   => (l, ")"+r, "ClosingBrace after Match")
              case _ => split(layout)
            }
          
          case (l: ValOrDefDef, _, r: ValOrDefDef) => 
            layout match {
              case Comma(l, r)   => (l, r, "Comma between ValDefs")
              case NewLine(l, r) => (l, r, "NewLine between ValDefs")
              case _ => split(layout)
            }
          
          case (l, parent: ValOrDefDef, r) if r.samePos(parent.rhs) && layout.contains("=") =>
            layout match {
              case Equals(l, r) => (l, r, "Equals after ValOrDefDef")
            }
            
          case (l, parent: ValOrDefDef, NoBlock(r)) if r.samePos(parent.rhs) && layout.contains("{") => 
            layout match {
              case OpeningCurlyBrace(l, r) => (l, "{"+ r, "OpeningCurlyBrace after ValOrDefDef")
            }
            
          case (l, _, r) => split(layout)
        }
        
        /**
         * We remove all leading or trailing commas, they always need to be re-introduced by the printers.
         */
        def removeLeadingOrTrailingComma(s: String) = {
          val CommaAtStart = ",\\s?(.*)".r
          val CommaAtEnd   = "(.*),\\s?".r
          s match {
            case CommaAtStart(rest) => rest
            case CommaAtEnd(rest) => rest
            case _ => s
          }
        }
        
        trace("Rule %s splits (%s, %s) layout %s into %s and %s", rule, l.getClass.getSimpleName, r.getClass.getSimpleName, layout, ll, lr)
        Layout(mergeLayoutWithComment(removeLeadingOrTrailingComma(ll), comments)) → Layout(mergeLayoutWithComment(removeLeadingOrTrailingComma(lr) reverse, comments reverse) reverse)
    }
  }
}