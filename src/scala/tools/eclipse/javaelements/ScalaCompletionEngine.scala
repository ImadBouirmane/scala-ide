/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.eclipse.javaelements

import scala.concurrent.SyncVar
import scala.util.NameTransformer

import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.jdt.internal.compiler.env
import org.eclipse.jdt.core.{
  CompletionContext, CompletionProposal, CompletionRequestor, ITypeRoot, JavaCore, WorkingCopyOwner }
import org.eclipse.jdt.core.compiler.CharOperation
import org.eclipse.jdt.internal.codeassist.{ InternalCompletionContext, InternalCompletionProposal }
import org.eclipse.jdt.internal.core.JavaProject
import org.eclipse.jdt.ui.text.java.CompletionProposalCollector

import scala.tools.nsc.symtab.Flags

import scala.tools.eclipse.util.ReflectionUtils

class ScalaCompletionEngine {
  def complete
    (cu : env.ICompilationUnit, unitToSkip : env.ICompilationUnit,
     position : Int,  requestor : CompletionRequestor, owner : WorkingCopyOwner, typeRoot : ITypeRoot,
     monitor : IProgressMonitor) {

    import InternalCompletionProposalUtils._

    val scu = cu.asInstanceOf[ScalaCompilationUnit]
    val javaProject = scu.getJavaProject.asInstanceOf[JavaProject]
    val environment = javaProject.newSearchableNameEnvironment(owner)
    
    def createProposal(kind : Int, completionOffset : Int) : InternalCompletionProposal = 
      CompletionProposal.create(kind, completionOffset).asInstanceOf[InternalCompletionProposal] 
    
    scu.withCompilerResult({ crh =>
    
      import crh._
      import compiler.{ javaType, mapModifiers, mapTypeName, mapParamTypeName, mapParamTypePackageName, nme }
      
      val pos = compiler.rangePos(sourceFile, position, position, position)
      
      val typed = new SyncVar[Either[compiler.Tree, Throwable]]
      compiler.askTypeAt(pos, typed)
      val t1 = typed.get.left.toOption
      val t0 = t1 match {
        case Some(tt : compiler.TypeTree) => Some(tt.original)
        case t => t 
      }
      
      val completed = new SyncVar[Either[List[compiler.Member], Throwable]]
      val (start, end) = t0 match {
        case Some(s@compiler.Select(qualifier, name)) =>
          val cpos0 = qualifier.pos.endOrPoint 
          val cpos = compiler.rangePos(sourceFile, cpos0, cpos0, cpos0)
          compiler.askTypeCompletion(cpos, completed)
          (s.pos.point min s.pos.endOrPoint, s.pos.endOrPoint)
        case Some(i@compiler.Import(expr, selectors)) =>
          def qual(tree : compiler.Tree): compiler.Tree = tree.symbol.info match {
            case compiler.analyzer.ImportType(expr) => expr
            case _ => tree
          }
          val cpos0 = qual(i).pos.endOrPoint
          val cpos = compiler.rangePos(sourceFile, cpos0, cpos0, cpos0)
          compiler.askTypeCompletion(cpos, completed)
          ((cpos0 + 1) min position, position)
        case _ =>
          val region = compiler.findCompletionPoint(scu.getBuffer, position)
          val cpos = if (region == null) pos else {
            val start = region.getOffset
            compiler.rangePos(sourceFile, start, start, start)
          }
          compiler.askScopeCompletion(cpos, completed)
          if (region == null)
            (position, position)
          else {
            val start0 = region.getOffset
            val end0 = start0+region.getLength
            (start0, end0)
          }
      }
      
      val prefix = scu.getBuffer.getText(start, end-start).trim.toArray
  
      def createContext = {
        new CompletionContext {
          import CompletionContext._
          
          override def getOffset() = position
          override def getToken() : Array[Char] = {
            scu.getBuffer.getText(start, end-start).toArray
          }
          override def getTokenKind() = TOKEN_KIND_NAME
          override def getTokenLocation() = 0
          override def getTokenStart() = start
          override def getTokenEnd() = end-1
        }
      }
      
      def acceptSymbol(sym : compiler.Symbol, tpe0 : compiler.Type, accessible : Boolean, inherited : Boolean, viaView : compiler.Symbol) {
        val tpe = compiler.uncurry.transformInfo(sym, tpe0)
        if (sym.hasFlag(Flags.ACCESSOR) || sym.hasFlag(Flags.PARAMACCESSOR)) {
          val proposal =  createProposal(CompletionProposal.FIELD_REF, position-1)
          val fieldTypeSymbol = tpe.resultType.typeSymbol
          val transformedName = NameTransformer.decode(sym.name.toString) 
          val relevance = if (inherited) 20 else if(viaView != compiler.NoSymbol) 10 else 30
          
          proposal.setDeclarationSignature(javaType(sym.owner.tpe).getSignature.replace('/', '.').toArray)
          proposal.setSignature(javaType(tpe).getSignature.replace('/', '.').toArray)
          setDeclarationPackageName(proposal, sym.enclosingPackage.fullNameString.toArray)
          setDeclarationTypeName(proposal, mapTypeName(sym.owner).toArray)
          setPackageName(proposal, fieldTypeSymbol.enclosingPackage.fullNameString.toArray)
          setTypeName(proposal, mapTypeName(fieldTypeSymbol).toArray)
          proposal.setName(transformedName.toArray)
          proposal.setCompletion(transformedName.toArray)
          proposal.setFlags(mapModifiers(sym))
          proposal.setReplaceRange(start, end)
          proposal.setTokenRange(start, end)
          proposal.setRelevance(relevance)
          requestor.accept(proposal)
        } else if (sym.isMethod && !sym.isConstructor && sym.name != nme.asInstanceOf_ && sym.name != nme.isInstanceOf_) {
          val proposal =  createProposal(CompletionProposal.METHOD_REF, position-1)
          val paramNames = tpe.paramss.flatMap(_.map(_.name))
          val paramTypes = tpe.paramss.flatMap(_.map(_.tpe))
          val resultTypeSymbol = tpe.finalResultType.typeSymbol
          val relevance = if (inherited) 20 else if(viaView != compiler.NoSymbol) 10 else 30
          
          val (transformedName, completion) = NameTransformer.decode(sym.name.toString) match {
            case n@("$asInstanceOf" | "$isInstanceOf") =>
              val n0 = n.substring(1) 
              (n0, n0+"[]")
            case n =>
              (n, n+"()")
          }
          
          val sig0 = javaType(tpe).getSignature.replace('/', '.')
          val sig = if (sig0.startsWith("(")) sig0 else "()"+sig0
          
          proposal.setDeclarationSignature(javaType(sym.owner.tpe).getSignature.replace('/', '.').toArray)
          proposal.setSignature(sig.toArray)
          setDeclarationPackageName(proposal, sym.enclosingPackage.fullNameString.toArray)
          setDeclarationTypeName(proposal, mapTypeName(sym.owner).toArray)
          setParameterPackageNames(proposal, paramTypes.map(mapParamTypePackageName(_).toArray).toArray)
          setParameterTypeNames(proposal, paramTypes.map(mapParamTypeName(_).toArray).toArray)
          setPackageName(proposal, resultTypeSymbol.enclosingPackage.fullNameString.toArray)
          setTypeName(proposal, mapTypeName(resultTypeSymbol).toArray)
          proposal.setName(transformedName.toArray)
          proposal.setCompletion(completion.toArray)
          proposal.setFlags(mapModifiers(sym))
          proposal.setReplaceRange(start, end)
          proposal.setTokenRange(start, end)
          proposal.setRelevance(relevance)
          proposal.setParameterNames(paramNames.map(_.toString.toArray).toArray)
          requestor.accept(proposal)
        } else if (sym.isTerm) {
          val proposal =  createProposal(CompletionProposal.LOCAL_VARIABLE_REF, position-1)
          val transformedName = NameTransformer.decode(sym.name.toString) 
          val relevance = 30
          proposal.setSignature(javaType(tpe).getSignature.replace('/', '.').toArray)
          setPackageName(proposal, tpe.typeSymbol.enclosingPackage.fullNameString.toArray)
          setTypeName(proposal, mapTypeName(tpe.typeSymbol).toArray)
          proposal.setName(transformedName.toArray)
          proposal.setCompletion(transformedName.toArray)
          proposal.setFlags(mapModifiers(sym))
          proposal.setReplaceRange(start, end)
          proposal.setTokenRange(start, end)
          proposal.setRelevance(relevance)
          requestor.accept(proposal)
        }
      }
      
      def nameMatches(sym : compiler.Symbol) = {
        val name = sym.name.toString.toArray 
        CharOperation.prefixEquals(prefix, name, false) ||
        CharOperation.camelCaseMatch(prefix, name)
      }
      
      def validType(sym : compiler.Symbol, tpe : compiler.Type) = {
        try {
          if (sym.isEmptyPackage || tpe == compiler.NoType || tpe == compiler.ErrorType)
            false
          else { 
            val tpe0 = compiler.uncurry.transformInfo(sym, tpe)
            javaType(sym.owner.tpe).getSignature
            javaType(tpe0).getSignature
            true
          }
        } catch {
          case _ => false
        }
      }
      
      completed.get.left.toOption match {
        case Some(completions) =>
          requestor.acceptContext(createContext)
          for(completion <- completions) {
            completion match {
              case compiler.TypeMember(sym, tpe, accessible, inherited, viaView) if nameMatches(sym) && validType(sym, tpe) =>
                acceptSymbol(sym, tpe, accessible, inherited, viaView)
              case compiler.ScopeMember(sym, tpe, accessible, _) if nameMatches(sym) && validType(sym, tpe) =>
                acceptSymbol(sym, tpe, accessible, false, compiler.NoSymbol)
              case _ =>
                //println("Not handled")
            }
          }
        case None =>
          println("No completions")
      }
    })
  }
}

object InternalCompletionProposalUtils extends ReflectionUtils {
  val icpClazz = classOf[InternalCompletionProposal]
  val setDeclarationPackageNameMethod = getDeclaredMethod(icpClazz, "setDeclarationPackageName", classOf[Array[Char]])
  val setDeclarationTypeNameMethod = getDeclaredMethod(icpClazz, "setDeclarationTypeName", classOf[Array[Char]])
  val setParameterPackageNamesMethod = getDeclaredMethod(icpClazz, "setParameterPackageNames", classOf[Array[Array[Char]]])
  val setParameterTypeNamesMethod = getDeclaredMethod(icpClazz, "setParameterTypeNames", classOf[Array[Array[Char]]])
  val setPackageNameMethod = getDeclaredMethod(icpClazz, "setPackageName", classOf[Array[Char]])
  val setTypeNameMethod = getDeclaredMethod(icpClazz, "setTypeName", classOf[Array[Char]])
  
  def setDeclarationPackageName(icp : InternalCompletionProposal, name : Array[Char]) { setDeclarationPackageNameMethod.invoke(icp, Array(name) : _*) }
  def setDeclarationTypeName(icp : InternalCompletionProposal, name : Array[Char]) { setDeclarationTypeNameMethod.invoke(icp, Array(name) : _*) }
  def setParameterPackageNames(icp : InternalCompletionProposal, names : Array[Array[Char]]) { setParameterPackageNamesMethod.invoke(icp, Array(names) : _*) }
  def setParameterTypeNames(icp : InternalCompletionProposal, names : Array[Array[Char]]) { setParameterTypeNamesMethod.invoke(icp, Array(names) : _*) }
  def setPackageName(icp : InternalCompletionProposal, name : Array[Char]) { setPackageNameMethod.invoke(icp, Array(name) : _*) }
  def setTypeName(icp : InternalCompletionProposal, name : Array[Char]) { setTypeNameMethod.invoke(icp, Array(name) : _*) }
}
