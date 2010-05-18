/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.refactoring
package implementations

import analysis.FullIndexes
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.interactive.Global
import common.Change
import sourcegen.Transformations

abstract class Rename extends MultiStageRefactoring with sourcegen.SourceGen with sourcegen.AstTransformations with common.PimpedTrees {
  
  import global._
  import Transformations._
  
  def treeForFile(file: AbstractFile) = {
    unitOfFile get file map (_.body) //flatMap removeAuxiliaryTrees
  }
    
  case class PreparationResult(selectedLocal: SymTree, hasLocalScope: Boolean)
  
  abstract class RefactoringParameters {
    def newName: String
  }
  
  def prepare(s: Selection) = {
    s.selectedSymbolTree match {
      case Some(t) =>
        Right(PreparationResult(t, s.findSelectedOfType[DefDef] map (_ != t) getOrElse false))
      case None => Left(PreparationError("no symbol selected found"))
    }
  }
    
  def perform(selection: Selection, prepared: PreparationResult, params: RefactoringParameters): Either[RefactoringError, TreeModifications] = {

    import params._
    
    trace("Selected tree is %s", prepared.selectedLocal)
    
    val occurences = index.occurences(prepared.selectedLocal.symbol) 
    
    occurences foreach (s => trace("Symbol is referenced at %s (%s:%s)", s, s.pos.source.file.name, s.pos.line))
    
    val canRename = predicate[Tree] {
      case t: Tree => occurences contains t 
    }
    
    val renameTree = sourcegen.Transformations.transform[Tree, Tree] {
      case s: SymTree => mkRenamedSymTree(s, newName)
      case t: TypeTree => 
      
        val newType = t.tpe map {
          case r @ RefinedType(_ :: parents, _) =>
            r.copy(parents = parents map {
              case TypeRef(_, sym, _) if sym == prepared.selectedLocal.symbol =>
                // we cheat 
                new Type {
                  override def safeToString: String = newName
                }
              case t => t 
            })
          case t => t
        }
      
        val typeTree = new TypeTree
        typeTree setType newType
        typeTree setPos t.pos
    }
    
    val rename = ↓(canRename &> renameTree |> id)
    
    val changes2 = occurences flatMap rename.apply
    
    
    val c3 = createChanges(changes2)
    
    

    val changes = new ModificationCollector {
      occurences foreach {
        transform2(_) {
          case t: Tree => occurences contains t
        } {
          
      case s: SymTree => mkRenamedSymTree(s, params.newName)
      case t: TypeTree => 
      
        val newType = t.tpe map {
          case r @ RefinedType(_ :: parents, _) =>
            r.copy(parents = parents map {
              case TypeRef(_, sym, _) if sym == prepared.selectedLocal.symbol =>
                NamedType(params.newName, null)
              case t => t 
            })
          case t => t
        }
      
        new TypeTree {
          tpe = newType
          pos = t.pos
        }
        }
      }
    }
    
    Right(changes)
  }
}
