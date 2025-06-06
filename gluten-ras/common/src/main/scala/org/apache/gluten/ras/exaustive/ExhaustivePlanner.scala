/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gluten.ras.exaustive

import org.apache.gluten.ras._
import org.apache.gluten.ras.Best.KnownCostPath
import org.apache.gluten.ras.best.BestFinder
import org.apache.gluten.ras.exaustive.ExhaustivePlanner.ExhaustiveExplorer
import org.apache.gluten.ras.memo.{Memo, MemoState}
import org.apache.gluten.ras.path._
import org.apache.gluten.ras.property.PropertySet
import org.apache.gluten.ras.rule.{EnforcerRuleSet, RuleApplier, Shape}

private class ExhaustivePlanner[T <: AnyRef] private (
    ras: Ras[T],
    constraintSet: PropertySet[T],
    plan: T)
  extends RasPlanner[T] {
  private val memo = Memo(ras)
  private val rules = ras.ruleFactory.create().map(rule => RuleApplier.regular(ras, memo, rule))
  private val enforcerRuleSetFactory = EnforcerRuleSet.Factory.regular(ras, memo)
  private val deriverRuleSetFactory = EnforcerRuleSet.Factory.derive(ras, memo)

  private lazy val rootGroupId: Int = {
    memo.memorize(plan, constraintSet +: ras.memoRoleDef.reqUser).id()
  }

  private lazy val best: (Best[T], KnownCostPath[T]) = {
    val groupId = rootGroupId
    explore()
    val memoState = memo.newState()
    val best = findBest(memoState, groupId)
    (best, best.path())
  }

  override def plan(): T = {
    best._2.rasPath.plan()
  }

  override def newState(): PlannerState[T] = {
    val foundBest = best._1
    PlannerState(ras, memo.newState(), rootGroupId, foundBest)
  }

  private def explore(): Unit = {
    // TODO1: Prune paths within cost threshold
    // ~~ TODO2: Use partial-canonical paths to reduce search space ~~
    memo.doExhaustively {
      val explorer = new ExhaustiveExplorer(
        ras,
        memo.newState(),
        rules,
        enforcerRuleSetFactory,
        deriverRuleSetFactory)
      explorer.explore()
    }
  }

  private def findBest(memoState: MemoState[T], groupId: Int): Best[T] = {
    BestFinder(ras, memoState).bestOf(groupId)
  }
}

object ExhaustivePlanner {
  def apply[T <: AnyRef](ras: Ras[T], constraintSet: PropertySet[T], plan: T): RasPlanner[T] = {
    new ExhaustivePlanner(ras, constraintSet, plan)
  }

  private class ExhaustiveExplorer[T <: AnyRef](
      ras: Ras[T],
      memoState: MemoState[T],
      rules: Seq[RuleApplier[T]],
      enforcerRuleSetFactory: EnforcerRuleSet.Factory[T],
      deriverRuleSetFactory: EnforcerRuleSet.Factory[T]) {
    private val allClusters = memoState.allClusters()
    private val allGroups = memoState.allGroups()

    def explore(): Unit = {
      // TODO: ONLY APPLY RULES ON ALTERED GROUPS (and close parents)
      applyHubRules()
      applyEnforcerRules()
      applyRules()
    }

    private def findPaths(gn: GroupNode[T], shapes: Seq[Shape[T]])(
        onFound: RasPath[T] => Unit): Unit = {
      val finder = shapes
        .foldLeft(
          PathFinder
            .builder(ras, memoState)) {
          case (builder, shape) =>
            builder.output(shape.wizard())
        }
        .build()
      finder.find(gn).foreach(path => onFound(path))
    }

    private def applyRule(rule: RuleApplier[T], icp: InClusterPath[T]): Unit = {
      rule.apply(icp)
    }

    private def applyRules(): Unit = {
      if (rules.isEmpty) {
        return
      }
      val shapes = rules.map(_.shape())
      memoState
        .clusterLookup()
        .foreach {
          case (cKey, cluster) =>
            val hubGroup = memoState.getHubGroup(cKey)
            findPaths(GroupNode(ras, hubGroup), shapes) {
              path => rules.foreach(rule => applyRule(rule, InClusterPath(cKey, path)))
            }
        }
    }

    private def applyHubRules(): Unit = {
      val hubConstraint = ras.hubConstraintSet()
      val hubDeriverRuleSet = deriverRuleSetFactory.ruleSetOf(hubConstraint)
      val hubDeriverRules = hubDeriverRuleSet.rules()
      if (hubDeriverRules.nonEmpty) {
        memoState
          .clusterLookup()
          .foreach {
            case (cKey, cluster) =>
              val hubDeriverRuleShapes = hubDeriverRuleSet.shapes()
              val userGroup = memoState.getUserGroup(cKey)
              findPaths(GroupNode(ras, userGroup), hubDeriverRuleShapes) {
                path => hubDeriverRules.foreach(rule => applyRule(rule, InClusterPath(cKey, path)))
              }
          }
      }
    }

    private def applyEnforcerRules(): Unit = {
      allGroups.foreach {
        group =>
          val constraintSet = group.constraintSet()
          val enforcerRuleSet = deriverRuleSetFactory.ruleSetOf(
            constraintSet) ++ enforcerRuleSetFactory.ruleSetOf(constraintSet)
          val enforcerRules = enforcerRuleSet.rules()
          if (enforcerRules.nonEmpty) {
            val enforcerRuleShapes = enforcerRuleSet.shapes()
            val cKey = group.clusterKey()
            val hubGroup = memoState.getHubGroup(cKey)
            findPaths(GroupNode(ras, hubGroup), enforcerRuleShapes) {
              path => enforcerRules.foreach(rule => applyRule(rule, InClusterPath(cKey, path)))
            }
          }
      }
    }
  }
}
