/*
 * Copyright 2005-2010 LAMP/EPFL
 */

package scala.tools.refactoring
package sourcegen

import common.Tracing
import common.PimpedTrees

/**
 * Provides a function that discovers all trees that have changed
 * and need to be regenerated.
 */
trait TreeChangesDiscoverer {
  
  this: Tracing with PimpedTrees with common.CompilerAccess =>
  
  import global._
  
  /**
   * Starting from a root tree, returns all children that have changed. The format
   * of the result is a tuple of a top-level tree, a position of the range that should 
   * be replaced and a set of all trees that changed in the context of that top-level 
   * tree, including the top-level tree.
   */
  def findAllChangedTrees(t: Tree): List[(Tree, Position, Set[Tree])] = {
    
    def hasTreeInternallyChanged(t: Tree): Boolean = findOriginalTree(t) map (t → _) getOrElse { 
        trace("original not found for tree %s", t)
        return true
      } match {
        case (t: NameTree, o: NameTree) => 
          t.nameString != o.nameString
        case (t: Literal, o: Literal) =>
          t.value != o.value
        case (t: Ident, o: Ident) =>
          t.nameString != o.nameString
        case (t: TypeTree, o: TypeTree) =>
          t != o
        case (t: Import, o: Import) =>
          t != o
        case _ => 
          false
      }
    
    def hasChangedChildren(t: Tree): Boolean = findOriginalTree(t) map children match {
      case None =>
        Predef.error("should never happen")
      case Some(origChld) =>
        !(children(t) corresponds origChld) { (t1, t2) => t1.samePosAndType(t2) }
    }
    
    def isSameAsOriginalTree(t: Tree) = {
      val originalTree = findOriginalTree(t) 
      originalTree map (_ eq t) getOrElse (false)
    }
    
    def searchChildrenForChanges(parent: Tree): List[Tree] = {
      
      def findChildren(t: Tree, parents: List[Tree]): List[Tree] = {
        if (isSameAsOriginalTree(t)) {
          trace("  Tree %s is unchanged.", t.getClass.getSimpleName)
          Nil
        } else if (hasTreeInternallyChanged(t)) { 
          trace("  Tree %s has changed internally.", t.getClass.getSimpleName)
          t :: parents ::: searchChildrenForChanges(t)
        } else if (hasChangedChildren(t)) {
          trace("  Tree %s has changed children.", t.getClass.getSimpleName)
          t :: parents ::: searchChildrenForChanges(t)
        } else {
          children(t) flatMap (c => findChildren(c, t :: parents))
        }
      }
      
      children(parent) flatMap (findChildren(_, Nil))
    }
    
    /*the default result when the tree has changed*/
    def resultWhenChanged = List((t, t.pos, Set(t) ++ searchChildrenForChanges(t)))
    
    if (isSameAsOriginalTree(t)) {
      trace("Top tree %s is unchanged.", t.getClass.getSimpleName)
      Nil
    } else if (hasTreeInternallyChanged(t)) {
      trace("Top tree %s has changed internally.", t.getClass.getSimpleName)
      resultWhenChanged
    } else if (hasChangedChildren(t)) {
      trace("Top tree %s has changed children.", t.getClass.getSimpleName)

      lazy val originalChildren = findOriginalTree(t) map children getOrElse Nil
      lazy val modifiedChildren = children(t)
      
      t match {
        case _: Block | _: Template if originalChildren.size == modifiedChildren.size =>
          
          originalChildren zip modifiedChildren filterNot {
            case (t1, t2) => t1.samePosAndType(t2)
          } match {
            case (orig, changed) :: Nil if changed.pos == NoPosition =>
              // only one statement in the block has changed, so we can rewrite just this one
              // because it does not have a position, we return the position of the stmt it
              // replaced
              trace("Replace only the single changed statement in the block.")
              List((changed, orig.pos, Set(changed) ++ searchChildrenForChanges(changed)))
            case _ =>
              resultWhenChanged
          }
     
        case _ =>
          resultWhenChanged          
      }
    } else {
      children(t) flatMap (c => findAllChangedTrees(c))
    }
  }
  
  def findTopLevelTrees(ts: List[Tree]) = {
       
    def properlyIncludes(t1: Tree, t2: Tree) = t1.pos.source == t2.pos.source && t1.pos.properlyIncludes(t2.pos)
    
    def findSuperTrees(trees: List[Tree], superTrees: List[Tree]): List[Tree] = trees match {
      case Nil => superTrees
      case t :: ts =>
      
        def mergeOverlappingTrees(ts: List[Tree]): List[Tree] = ts match {
          case Nil => t :: Nil
          case x :: xs if properlyIncludes(x, t) => x :: xs
          case x :: xs if properlyIncludes(t, x) => t :: xs
          case x :: xs => x :: mergeOverlappingTrees(xs)
        }
      
        findSuperTrees(ts, mergeOverlappingTrees(superTrees))
    }
    
    findSuperTrees(ts, Nil)
  }
}