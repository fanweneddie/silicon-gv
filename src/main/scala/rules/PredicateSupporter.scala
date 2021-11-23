// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2019 ETH Zurich.

package viper.silicon.rules

import viper.silver.ast
import viper.silver.verifier.PartialVerificationError
import viper.silver.verifier.reasons.InsufficientPermission
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.interfaces.VerificationResult
import viper.silicon.resources.PredicateID
import viper.silicon.state._
import viper.silicon.state.terms._
import viper.silicon.supporters.Translator
import viper.silicon.utils.toSf
import viper.silicon.verifier.Verifier

trait PredicateSupportRules extends SymbolicExecutionRules {
  def fold(s: State,
           predicate: ast.Predicate,
           tArgs: List[Term],
           tPerm: Term,
           constrainableWildcards: InsertionOrderedSet[Var],
           pve: PartialVerificationError,
           v: Verifier)
          (Q: (State, Verifier) => VerificationResult)
          : VerificationResult

  def unfold(s: State,
             predicate: ast.Predicate,
             tArgs: List[Term],
             tPerm: Term,
             constrainableWildcards: InsertionOrderedSet[Var],
             pve: PartialVerificationError,
             v: Verifier,
             pa: ast.PredicateAccess /* TODO: Make optional */)
            (Q: (State, Verifier) => VerificationResult)
            : VerificationResult
}

object predicateSupporter extends PredicateSupportRules with Immutable {
  import consumer._
  import producer._

  def fold(s: State,
           predicate: ast.Predicate,
           tArgs: List[Term],
           tPerm: Term,
           constrainableWildcards: InsertionOrderedSet[Var],
           pve: PartialVerificationError,
           v: Verifier)
          (Q: (State, Verifier) => VerificationResult)
          : VerificationResult = {

    val body = predicate.body.get /* Only non-abstract predicates can be unfolded */
    val gIns = s.g + Store(predicate.formalArgs map (_.localVar) zip tArgs)
    val s1 = s.copy(g = gIns,
                    smDomainNeeded = true)
              .scalePermissionFactor(tPerm)
    consume(s1, body, pve, v)((s1a, snap, v1) => {
      val predTrigger = App(Verifier.predicateData(predicate).triggerFunction,
                            snap.convert(terms.sorts.Snap) +: tArgs)
      v1.decider.assume(predTrigger)
      val s2 = s1a.setConstrainable(constrainableWildcards, false)
      if (s2.qpPredicates.contains(predicate)) {
        val predSnap = snap.convert(s2.predicateSnapMap(predicate))
        val formalArgs = s2.predicateFormalVarMap(predicate)
        val (sm, smValueDef) =
          quantifiedChunkSupporter.singletonSnapshotMap(s2, predicate, tArgs, predSnap, v1)
        v1.decider.prover.comment("Definitional axioms for singleton-SM's value")
        v1.decider.assume(smValueDef)
        val ch =
          quantifiedChunkSupporter.createSingletonQuantifiedChunk(
            formalArgs, predicate, tArgs, tPerm, sm)
        val h3 = s2.h + ch
        val smDef = SnapshotMapDefinition(predicate, sm, Seq(smValueDef), Seq())
        val smCache = {
          val (relevantChunks, _) =
            quantifiedChunkSupporter.splitHeap[QuantifiedPredicateChunk](h3, BasicChunkIdentifier(predicate.name))
          val (smDef1, smCache1) =
            quantifiedChunkSupporter.summarisingSnapshotMap(
              s2, predicate, s2.predicateFormalVarMap(predicate), relevantChunks, v1)
          v1.decider.assume(PredicateTrigger(predicate.name, smDef1.sm, tArgs))

          smCache1
        }

        val s3 = s2.copy(g = s.g,
                         h = h3,
                         smCache = smCache,
                         functionRecorder = s2.functionRecorder.recordFvfAndDomain(smDef))
        Q(s3, v1)
      } else {
        val ch = BasicChunk(PredicateID, BasicChunkIdentifier(predicate.name), tArgs, snap.convert(sorts.Snap), tPerm)
        val s3 = s2.copy(g = s.g,
                         smDomainNeeded = s.smDomainNeeded,
                         permissionScalingFactor = s.permissionScalingFactor)
        chunkSupporter.produce(s3, s3.h, ch, v1)((s4, h1, v2) =>
          Q(s4.copy(h = h1), v2))
      }
    })
  }

  // same as consume case for predicates; add profiling here!
  def unfold(s: State,
             predicate: ast.Predicate,
             tArgs: List[Term],
             tPerm: Term,
             constrainableWildcards: InsertionOrderedSet[Var],
             pve: PartialVerificationError,
             v: Verifier,
             pa: ast.PredicateAccess)
            (Q: (State, Verifier) => VerificationResult)
            : VerificationResult = {

    val gIns = s.g + Store(predicate.formalArgs map (_.localVar) zip tArgs)
    val body = predicate.body.get /* Only non-abstract predicates can be unfolded */
    val s1 = s.scalePermissionFactor(tPerm)
    if (s1.qpPredicates.contains(predicate)) {
      val formalVars = s1.predicateFormalVarMap(predicate)
      quantifiedChunkSupporter.consumeSingleLocation(
        s1,
        s1.h,
        formalVars,
        tArgs,
        pa,
        tPerm,
        None,
        pve,
        v
      )((s2, h2, snap, v1) => {
        val s3 = s2.copy(g = gIns, h = h2)
                   .setConstrainable(constrainableWildcards, false)
        produce(s3, toSf(snap), body, pve, v1)((s4, v2) => {
          v2.decider.prover.saturate(Verifier.config.z3SaturationTimeouts.afterUnfold)
          val predicateTrigger =
            App(Verifier.predicateData(predicate).triggerFunction,
                snap.convert(terms.sorts.Snap) +: tArgs)
          v2.decider.assume(predicateTrigger)
          Q(s4.copy(g = s.g), v2)})
      })
      // profiling here?
    } else {
      val ve = pve dueTo InsufficientPermission(pa)
      val description = s"consume ${pa.pos}: $pa"
      val s2 = stateConsolidator.consolidate(s1, v)
      chunkSupporter.consume(s2, s2.h, predicate, tArgs, s2.permissionScalingFactor, ve, v, description)((s3, h1, snap1, v1, status) => {
          
          profilingInfo.incrementTotalConjuncts

          if (s3.isImprecise) {
            chunkSupporter.consume(s3, s3.optimisticHeap, predicate, tArgs, s3.permissionScalingFactor, ve, v1, description)((s4, oh1, snap2, v2, status1) => {
              if (!status && !status1) {
                runtimeChecks.addChecks(pa,
                  ast.PredicateAccessPredicate(pa, ast.FullPerm()())(),
                  v2.decider.pcs.branchConditions.map(branch =>
                      new Translator(s4, v2.decider.pcs).translate(branch)),
                    v2.decider.pcs.branchConditionsAstNodes,
                    pa,
                    true)
                pa.addCheck(ast.PredicateAccessPredicate(pa, ast.FullPerm()())())
              }
              if (status) {

                profilingInfo.incrementEliminatedConjuncts

                val s5 = s4.copy(g = gIns, h = h1, optimisticHeap = oh1)
                  .setConstrainable(constrainableWildcards, false)
                produce(s5, toSf(snap1), body, pve, v2)((s6, v3) => {
                  v3.decider.prover.saturate(Verifier.config.z3SaturationTimeouts.afterUnfold)
                  val predicateTrigger =
                    App(Verifier.predicateData(predicate).triggerFunction, snap1 +: tArgs)
                  v3.decider.assume(predicateTrigger)
                  val s7 = s6.copy(g = s4.g,
                    permissionScalingFactor = s.permissionScalingFactor)
                  Q(s7, v3)
                })
              } else {

                profilingInfo.incrementEliminatedConjuncts

                val s5 = s4.copy(g = gIns, h = h1, optimisticHeap = oh1)
                  .setConstrainable(constrainableWildcards, false)
                produce(s5, toSf(snap2), body, pve, v2)((s6, v3) => {
                  v3.decider.prover.saturate(Verifier.config.z3SaturationTimeouts.afterUnfold)
                  val predicateTrigger =
                    App(Verifier.predicateData(predicate).triggerFunction, snap2 +: tArgs)
                  v3.decider.assume(predicateTrigger)
                  val s7 = s6.copy(g = s4.g,
                    permissionScalingFactor = s.permissionScalingFactor)
                  Q(s7, v3)
                })
              }
            })
          } else if (status) {

            profilingInfo.incrementEliminatedConjuncts

            val s4 = s3.copy(g = gIns, h = h1)
              .setConstrainable(constrainableWildcards, false)
            produce(s4, toSf(snap1), body, pve, v1)((s5, v2) => {
              v2.decider.prover.saturate(Verifier.config.z3SaturationTimeouts.afterUnfold)
              val predicateTrigger =
                App(Verifier.predicateData(predicate).triggerFunction, snap1 +: tArgs)
              v2.decider.assume(predicateTrigger)
              val s6 = s5.copy(g = s3.g,
                permissionScalingFactor = s.permissionScalingFactor)
              Q(s6, v2)
            })
          } else {
            createFailure(ve, v1, s3)
          }
      })
    }
  }

/* NOTE: Possible alternative to storing the permission scaling factor in the context
 *       or passing it to produce/consume as an explicit argument.
 *       Carbon uses Permissions.multiplyExpByPerm as well (but does not extend the
 *       store).
 */
//    private def scale(γ: ST, body: ast.Exp, perm: Term) = {
//      /* TODO: Ensure that variable name does not clash with any Silver identifier already in use */
//      val scaleFactorVar = ast.LocalVar(identifierFactory.fresh("p'unf").name)(ast.Perm)
//      val scaledBody = ast.utility.Permissions.multiplyExpByPerm(body, scaleFactorVar)
//
//      (γ + (scaleFactorVar -> perm), scaledBody)
//    }
}
