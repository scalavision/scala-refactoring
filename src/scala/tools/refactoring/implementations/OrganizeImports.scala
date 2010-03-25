package scala.tools.refactoring.implementations

import scala.tools.refactoring.MultiStageRefactoring
import scala.tools.refactoring.analysis.FullIndexes
import scala.collection.mutable.ListBuffer
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.interactive.Global
import scala.tools.refactoring.common.Change

abstract class OrganizeImports extends MultiStageRefactoring {
  
  import global._
  
  class PreparationResult
  
  class RefactoringParameters
  
  def prepare(s: Selection): Either[PreparationError, PreparationResult] = Right(new PreparationResult)
    
  def perform(selection: Selection, prepared: PreparationResult, params: RefactoringParameters): Either[RefactoringError, TreeModifications] = {
    
    val sortImports: List[Tree] => List[Tree] = _.sortWith({
      case (t1: Import, t2: Import) => t1.expr.toString < t2.expr.toString
    }: (Tree, Tree) => Boolean)
    
    val collapseImports: List[Tree] => List[Tree] = _.foldRight(List[Import]()) { 
      case (imp: Import, Nil) => imp :: Nil
      case (imp: Import, x :: xs) if imp.expr.toString == x.expr.toString => x.copy(selectors = x.selectors ::: imp.selectors).setPos(x.pos) :: xs
      case (imp: Import, xs) => imp :: xs
    }
    
    val simplifyWildcards: List[Tree] => List[Tree] = {
      def importsAll(i: ImportSelector) = i.name == nme.WILDCARD
      def renames(i: ImportSelector) = i.name != i.rename
      
      _ map {
        case imp @ Import(_, selectors) if selectors.exists(importsAll) && !selectors.exists(renames) => 
          imp.copy(selectors = selectors.filter(importsAll)).setPos(imp.pos)
        case imp => 
          imp
      }
    }
    
    var changes = new ModificationCollector {
      transform(selection.file) {
        case p @ PackageDef(_, stats) => {
          
          val sorted = stats partition {
            case _: Import => true
            case _ => false
          } match {
            case (imports, others) => (sortImports andThen collapseImports andThen simplifyWildcards apply imports) ::: others
          }
          
          p copy (stats = sorted) setPos p.pos
        }
      }
    }
    
    Right(changes)
  }
}