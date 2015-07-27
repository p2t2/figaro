/*
 * Gibbs.scala
 * A Gibbs sampler that operates on factor graphs.
 *
 * Created By:      William Kretschmer (kretsch@mit.edu)
 * Creation Date:   June 3, 2015
 *
 * Copyright 2015 Avrom J. Pfeffer and Charles River Analytics, Inc.
 * See http://www.cra.com or email figaro@cra.com for information.
 *
 * See http://www.github.com/p2t2/figaro for a copy of the software license.
 */

package com.cra.figaro.experimental.factored

import com.cra.figaro.algorithm.factored._
import com.cra.figaro.algorithm.factored.factors._
import com.cra.figaro.algorithm.lazyfactored._
import com.cra.figaro.algorithm.sampling._
import com.cra.figaro.language._
import com.cra.figaro.util._
import scala.annotation.tailrec
import scala.collection.mutable.Map

trait Gibbs[T] extends BaseUnweightedSampler with FactoredAlgorithm[T] {
  /**
   * The universe in which this Gibbs sampler is to be applied.
   */
  val universe: Universe

  /**
   * Semiring for use in factors.
   */
  val semiring: Semiring[T]

  /**
   * Elements whose samples will be recorded at each iteration.
   */
  val targetElements: List[Element[_]]

  /**
   * List of all factors.
   */
  var factors: List[Factor[T]] = _

  /**
   * Variables to sample at each time step.
   */
  var variables: Set[Variable[_]] = _

  /**
   * The most recent set of samples, used for sampling variables conditioned on the values of other variables.
   */
  val currentSamples: Map[Variable[_], Int] = Map()

  /**
   * Number of samples to throw away initially.
   */
  val burnIn: Int

  /**
   * Iterations thrown away between samples.
   */
  val interval: Int

  /**
   * Method to create a blocking scheme given information about the model and factors.
   */
  def createBlocks: List[List[Variable[_]]]

  /**
   * List of samplers, used for performing specialized block sampling.
   */
  protected var blockSamplers: List[BlockSampler] = _

  /**
   * Function to create a BlockSampler from a block.
   */
  val blockToSampler: Gibbs.BlockInfo => BlockSampler
}

trait ProbabilisticGibbs extends Gibbs[Double] {
  val semiring = SumProductSemiring()

  def getFactors(neededElements: List[Element[_]], targetElements: List[Element[_]], upperBounds: Boolean = false): List[Factor[Double]] = {
    Factory.removeFactors()
    val thisUniverseFactors = neededElements flatMap (Factory.make(_))
    val dependentUniverseFactors =
      for { (dependentUniverse, evidence) <- dependentUniverses } yield Factory.makeDependentFactor(universe, dependentUniverse, dependentAlgorithm(dependentUniverse, evidence))
    dependentUniverseFactors ::: thisUniverseFactors
  }

  // Update a list of samples one-by-one, conditioned on intermediate samples of all the other variables
  // This essentially performs a single step of blocked Gibbs sampling
  def sampleAllBlocks(): Unit = {
    blockSamplers.foreach(_.sample(currentSamples))
  }

  // Perform an iteration of sampling and record the results
  def sample(): (Boolean, Sample) = {
    sampleAllBlocks()
    val result = targetElements.flatMap(e => {
      val variable = Variable(e)
      val extended = variable.range(currentSamples(variable))
      // Accept the sample unless it is star
      if(extended.isRegular) {
        val samplePair: (Element[_], Any) = (e, extended.value)
        Some(samplePair)
      }
      else None
    })
    (result.length == targetElements.length, collection.mutable.Map(result:_*))
  }

  protected override def doSample() = {
    // This takes care of the thinning defined by interval
    for(i <- 1 until interval) {
      sampleAllBlocks()
    }
    super.doSample()
  }
}

abstract class ProbQueryGibbs(override val universe: Universe, targets: Element[_]*)(
  val dependentUniverses: List[(Universe, List[NamedEvidence[_]])],
  val dependentAlgorithm: (Universe, List[NamedEvidence[_]]) => () => Double,
  val burnIn: Int, val interval: Int,
  val blockToSampler: Gibbs.BlockInfo => BlockSampler, upperBounds: Boolean = false)
  extends BaseUnweightedSampler(universe, targets:_*)
  with ProbabilisticGibbs with UnweightedSampler {

  val targetElements = targets.toList

  def createBlocks(): List[List[Variable[_]]] = {
    // Maps each variable to its deterministic parents, i.e. variables whose blocks we should add to
    val variableParents: collection.immutable.Map[Variable[_], Set[Variable[_]]] = variables.map(_ match {
      case ev: ElementVariable[_] => ev.element match {
        // For Chain, we treat all of the result variables as deterministic parents
        case c: Chain[_, _] => {
          val chainResults: Set[Variable[_]] = LazyValues(universe).getMap(c).values.map(Variable(_)).toSet
          (ev, chainResults)
        }
        // For Apply, we take the deterministic parents to be its arguments
        case a: Apply[_] => (ev, a.args.map(Variable(_)).toSet)
        // Otherwise, we assume (perhaps incorrectly) that the variable is stochastic
        // It will not be blocked with any parents
        case _ => (ev, Set[Variable[_]]())
      }

      // This handles internal variables in Chains
      // We treat all of the result variables, as well as the parent variable, as deterministic parents
      case icv: InternalChainVariable[_, _] => {
        val chain = icv.chain.element.asInstanceOf[Chain[_, _]]
        val chainResults: Set[Variable[_]] = LazyValues(universe).getMap(chain).values.map(Variable(_)).toSet
        (icv, chainResults + Variable(chain.parent))
      }

      // Otherwise, we assume (perhaps incorrectly) that the variable is stochastic
      // It will not be blocked with any parents
      case v => (v, Set[Variable[_]]())
    }).toMap

    // Maps each variable to its deterministic children, i.e. variables that should be included in a block with this variable
    val variableChildren: collection.immutable.Map[Variable[_], Set[Variable[_]]] =
      variables.map(v => v -> variables.filter(variableParents(_).contains(v))).toMap

    // Start with the purely stochastic variables with no parents
    val starterVariables = variables.filter(variableParents(_).isEmpty)

    @tailrec
    // Recursively add deterministic children to the block
    def expandBlock(expand: Set[Variable[_]], block: Set[Variable[_]] = Set()): List[Variable[_]] = {
      if(expand.isEmpty) block.toList
      else {
        val expandNext = expand.flatMap(variableChildren(_))
        expandBlock(expandNext, block ++ expand)
      }
    }
    starterVariables.map(v => expandBlock(Set(v))).toList
  }

  override def initialize() = {
    super.initialize()
    // Get the needed elements, factors, and blocks
    val neededElements = getNeededElements(targetElements, Int.MaxValue)._1
    factors = getFactors(neededElements, targetElements, upperBounds)
    variables = factors.flatMap(_.variables).toSet
    val blocks = createBlocks()
    // Create block samplers
    blockSamplers = blocks.map(block => blockToSampler((block, factors.filter(_.variables.exists(block.contains(_))))))
    // Initialize the samples to a valid state and take the burn-in samples
    val initialSample = WalkSAT(factors)
    variables.foreach(v => currentSamples(v) = initialSample(v))
    for(_ <- 1 to burnIn) sampleAllBlocks()
  }
}



object Gibbs {
  // Information passed to BlockSampler constructor
  type BlockInfo = (List[Variable[_]], List[Factor[Double]])

  /**
   * Create a one-time Gibbs sampler using the given number of samples and target elements.
   */
  def apply(mySamples: Int, targets: Element[_]*)(implicit universe: Universe) =
    new ProbQueryGibbs(universe, targets: _*)(
      List(),
      (u: Universe, e: List[NamedEvidence[_]]) => () => ProbEvidenceSampler.computeProbEvidence(10000, e)(u),
      0, 1, BlockSampler.default) with OneTimeProbQuerySampler {val numSamples = mySamples}

  /**
   * Create a one-time Gibbs sampler using the given number of samples, the number of samples to burn in,
   * the sampling interval, the BlockSampler generator, and target elements.
   */
  def apply(mySamples: Int, burnIn: Int, interval: Int, blockToSampler: BlockInfo => BlockSampler, targets: Element[_]*)(implicit universe: Universe) =
    new ProbQueryGibbs(universe, targets: _*)(
      List(),
      (u: Universe, e: List[NamedEvidence[_]]) => () => ProbEvidenceSampler.computeProbEvidence(10000, e)(u),
      burnIn, interval, blockToSampler) with OneTimeProbQuerySampler {val numSamples = mySamples}

  /**
   * Create a one-time Gibbs sampler using the given dependent universes and algorithm,
   * the number of samples, the number of samples to burn in,
   * the sampling interval, the BlockSampler generator, and target elements.
   */
  def apply(dependentUniverses: List[(Universe, List[NamedEvidence[_]])],
    dependentAlgorithm: (Universe, List[NamedEvidence[_]]) => () => Double,
    mySamples: Int, burnIn: Int, interval: Int, blockToSampler: BlockInfo => BlockSampler, targets: Element[_]*)(implicit universe: Universe) =
    new ProbQueryGibbs(universe, targets: _*)(
      dependentUniverses,
      dependentAlgorithm,
      burnIn, interval, blockToSampler) with OneTimeProbQuerySampler {val numSamples = mySamples}

  /**
   * Create an anytime Gibbs sampler using the given target elements.
   */
  def apply(targets: Element[_]*)(implicit universe: Universe) =
    new ProbQueryGibbs(universe, targets: _*)(
      List(),
      (u: Universe, e: List[NamedEvidence[_]]) => () => ProbEvidenceSampler.computeProbEvidence(10000, e)(u),
      0, 1, BlockSampler.default) with AnytimeProbQuerySampler

  /**
   * Create an anytime Gibbs sampler using the given number of samples to burn in,
   * the sampling interval, the BlockSampler generator, and target elements.
   */
  def apply(burnIn: Int, interval: Int, blockToSampler: BlockInfo => BlockSampler, targets: Element[_]*)(implicit universe: Universe) =
    new ProbQueryGibbs(universe, targets: _*)(
      List(),
      (u: Universe, e: List[NamedEvidence[_]]) => () => ProbEvidenceSampler.computeProbEvidence(10000, e)(u),
      burnIn, interval, blockToSampler) with AnytimeProbQuerySampler

  /**
   * Create an anytime Gibbs sampler using the given dependent universes and algorithm,
   * the number of samples to burn in, the sampling interval,
   * the BlockSampler generator, and target elements.
   */
  def apply(dependentUniverses: List[(Universe, List[NamedEvidence[_]])],
    dependentAlgorithm: (Universe, List[NamedEvidence[_]]) => () => Double,
    burnIn: Int, interval: Int, blockToSampler: BlockInfo => BlockSampler, targets: Element[_]*)(implicit universe: Universe) =
    new ProbQueryGibbs(universe, targets: _*)(
      dependentUniverses,
      dependentAlgorithm,
      burnIn, interval, blockToSampler) with AnytimeProbQuerySampler
}