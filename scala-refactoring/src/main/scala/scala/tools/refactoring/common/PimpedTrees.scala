package scala.tools.refactoring
package common

import tools.nsc.io.AbstractFile
import tools.nsc.util.RangePosition
import tools.nsc.symtab.{Flags, Names, Symbols}
import reflect.ClassManifest.fromClass

/**
 * A bunch of implicit conversions for ASTs and other helper
 * functions that work on trees. Users of the trait need to
 * provide the means to access a file's corresponding tree.
 * 
 * */
trait PimpedTrees extends AdditionalTreeMethods with CustomTrees {
  
  val global: scala.tools.nsc.interactive.Global
  import global._

  /**
   * Returns the tree that is contained in this file.
   * Typically done with global.unitOfFile.
   * */
  def treeForFile(file: AbstractFile): Option[Tree]
    
  /**
   * Returns the compilation unit root for that position.
   * */  
  def cuRoot(p: Position): Option[Tree] = if (p == NoPosition) None else treeForFile(p.source.file)

  /**
   * Given a Position, returns the tree in that compilation
   * unit that inhabits that position.
   * */
  def findOriginalTreeFromPosition(p: Position): Option[List[Tree]] = {
    
    def find(t: Tree): List[Tree] = {
      (if(t samePos p)
        t :: Nil
      else 
        Nil) ::: children(t).map(find).flatten
    }
    
    cuRoot(p) map find
  }

  /**
   * Find a tree by its position and make sure that the trees
   * or of the same type. This is necessary because some trees
   * have the same position, for example, a compilation unit
   * without an explicit package and just a single top level
   * class, then the package and the class will have the same
   * position.
   * 
   * If multiple trees are candidates, then take the last one, 
   * because it is likely more specific.
   * */
  def findOriginalTree(t: Tree): Option[Tree] = findOriginalTreeFromPosition(t.pos) flatMap (_ filter (_ sameTree t ) lastOption)
  
  
  implicit def additionalTemplateMethods(t: Template) = new {
    def constructorParameters = t.body.filter {
      case ValDef(mods, _, _, _) => mods.hasFlag(Flags.CASEACCESSOR) || mods.hasFlag(Flags.PARAMACCESSOR) 
      case _ => false
    }
    
    def primaryConstructor = t.body.filter {
      case t: DefDef => t.symbol.isPrimaryConstructor
      case _ => false
    }
    
    def earlyDefs = t.body.collect {
      case t @ DefDef(_, _, _, _, _, BlockExtractor(stats)) if t.symbol.isConstructor => stats filter treeInfo.isEarlyDef
      case t @ DefDef(_, _, _, _, _, rhs)        if t.symbol.isConstructor && treeInfo.isEarlyDef(rhs) => rhs :: Nil
    } flatten
    
    def superConstructorParameters = t.body.collect {
      case t @ DefDef(_, _, _, _, _, BlockExtractor(stats)) if t.symbol.isConstructor => stats collect {
        case Apply(Super(_, _), args) => args
      } flatten
    } flatten
  }  
  
      
  /**
   * Name objects are not trees, this extractor creates NameTree instances from Trees.
   * */
  implicit def nameTreeToNameTreeExtractor(t: global.Tree) = new {
    object Name {
      def unapply(name: global.Name) = {
        Some(NameTree(name) setPos t.namePosition)
      }
    }
  }
  
  /**
   * Provides a finer-grained extractor for Template that distinguishes
   * between class constructor parameters, early definitions, parents, 
   * self type annotation and the real body.
   * */
  object TemplateExtractor {
    def unapply(t: Tree) = t match {
      case tpl: Template => 
              
        val classParams = tpl.constructorParameters
        
        val body = (tpl.body filterNot (tpl.primaryConstructor ::: classParams contains)) filterNot (_.isEmpty)
        
        val parents = (tpl.superConstructorParameters match {
          case Nil => tpl.parents
          case params => SuperConstructorCall(tpl.parents.head, params) :: tpl.parents.tail
        }) filterNot (_.isEmpty)
        
        val self = if(tpl.self.isEmpty) EmptyTree else {
          
          if(tpl.pos.isRange) {
            val source = tpl.self.pos.source.content.slice(tpl.self.pos.point, tpl.self.pos.end) mkString // XXX remove comments
            
            def extractExactPositionsOfAllTypes(typ: Type): List[NameTree] = typ match {
              case RefinedType(_ :: parents, _) =>
                parents flatMap extractExactPositionsOfAllTypes
              case TypeRef(_, sym, _) =>
                val thisName = sym.name.toString
                val start = tpl.self.pos.point + source.indexOf(thisName)
                val end = start + thisName.length
                List(NameTree(sym.name) setPos (tpl.self.pos withStart start withEnd end))
              case _ => Nil
            }
            
            val selfTypes = extractExactPositionsOfAllTypes(tpl.self.tpt.tpe)
            val namePos = {
              val p = tpl.self.pos
              p withEnd (if(p.start == p.point) p.end else p.point)
            }
            
            SelfTypeTree(NameTree(tpl.self.name) setPos namePos, selfTypes) setPos tpl.self.pos
          } else {
            tpl.self
          }
        }

        Some((classParams, tpl.earlyDefs, parents, self, body))
      
      case _ => 
        None
    }
  }
  
  
  /**
   * Returns all children that have a representation in the source code.
   * This includes Name and Modifier trees and excludes everything that
   * has no Position or is an EmptyTree.
   * */
  def children(t: Tree): List[Tree] = (t match {
    
    case PackageDef(pid, stats) => 
      pid :: stats
    
    case t @ ClassDef(ModifierTree(mods), name, tparams, impl) =>
      mods ::: (NameTree(name) setPos t.namePosition) :: tparams ::: impl :: Nil
      
    case t @ ModuleDef(ModifierTree(mods), name, impl) =>
      mods ::: (NameTree(name) setPos t.namePosition) :: impl :: Nil
      
    case TemplateExtractor(params, earlyBody, parents, self, body) =>
      params ::: earlyBody ::: parents ::: self :: body

    case t @ ValDef(ModifierTree(mods), name, tpt, rhs) =>
      mods ::: (NameTree(name) setPos t.namePosition) :: tpt :: rhs :: Nil
     
    case t @ DefDef(ModifierTree(mods), name, tparams, vparamss, tpt, rhs) =>
      mods ::: (NameTree(name) setPos t.namePosition) :: tparams ::: vparamss.flatten ::: tpt :: rhs :: Nil
     
    case t: TypeTree =>
      if(t.original != null) t.original :: Nil else Nil
      
    case AppliedTypeTree(tpt, args) =>
      tpt :: args
      
    case TypeDef(ModifierTree(mods), name, tparams, rhs) =>
      mods ::: (NameTree(name) setPos t.namePosition) :: tparams ::: rhs :: Nil
    
    case _: Literal | _: Ident | _: ModifierTree | _: NameTree | _: This => Nil
    
    case Apply(fun, args) =>
      fun :: args
      
    case t @ Select(qualifier: This, selector) if qualifier.pos == NoPosition && t.pos.start == t.pos.point =>
      (NameTree(selector) setPos t.namePosition) :: Nil
      
    case t @ Select(qualifier, selector) =>
      qualifier :: (NameTree(selector) setPos t.namePosition) :: Nil
      
    case BlockExtractor(stats) =>
      stats
      
    case Return(expr) =>
      expr :: Nil
      
    case New(tpt) =>
      tpt :: Nil
      
    case t @ Import(expr, _) =>
      expr :: t.Selectors()
      
    case ImportSelectorTree(name, rename) =>
      name :: rename :: Nil
      
    case SuperConstructorCall(clazz, args) =>
      clazz :: args
      
    case SelfTypeTree(name, types) =>
      name :: types
      
    case TypeApply(fun, args) =>
      fun :: args
      
    case Function(vparams, body) =>
      vparams ::: body :: Nil
      
    case If(cond, thenp, elsep) =>
      cond :: thenp :: elsep :: Nil
      
    case TypeBoundsTree(lo, hi) =>
      lo :: hi :: Nil
    
    case _ => throw new Exception("Unhandled tree: "+ t.getClass.getSimpleName)
     
  }) filterNot (_.isEmpty)
  
}