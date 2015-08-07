package org.broadinstitute.k3.variant

import java.io._

import org.apache.spark.broadcast.Broadcast

import scala.collection.Map
import scala.language.implicitConversions

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SQLContext

import scala.reflect.ClassTag

import org.broadinstitute.k3.Utils._

// FIXME implement variants WithKeys
class VariantSampleMatrix[T](val sampleIds: Array[String],
                             val rdd: RDD[(Variant, GenotypeStream)],
                             mapFn: (Variant, Int, Genotype) => T,
                             samplePredicate: (Int) => Boolean) {
  def nSamples: Int = sampleIds.length

  def cache(): VariantSampleMatrix[T] = {
    val localSamplePredicate = samplePredicate
    val localMapFn = mapFn
    new VariantSampleMatrix[T](sampleIds, rdd.cache(), localMapFn, localSamplePredicate)
  }

  def count(): Long = rdd.count()

  def mapValuesWithKeys[U](f: (Variant, Int, T) => U): VariantSampleMatrix[U] = {
    val localSamplePredicate = samplePredicate
    val localMapFn = mapFn
    new VariantSampleMatrix[U](sampleIds, rdd, (v, s, g) => f(v, s, localMapFn(v, s, g)), localSamplePredicate)
  }

  def mapValues[U](f: (T) => U): VariantSampleMatrix[U] = {
    val localSamplePredicate = samplePredicate
    val localMapFn = mapFn
    new VariantSampleMatrix[U](sampleIds, rdd, (v, s, g) => f(localMapFn(v, s, g)), localSamplePredicate)
  }

  // FIXME push down into reader: add VariantSampleDataframeMatrix?
  def filterVariants(p: (Variant) => Boolean) = {
    val localSamplePredicate = samplePredicate
    val localMapFn = mapFn
    new VariantSampleMatrix(sampleIds, rdd.filter(t => p(t._1)), localMapFn, localSamplePredicate)
  }

  def filterSamples(p: (Int) => Boolean) = {
    val localSamplePredicate = samplePredicate
    val localMapFn = mapFn
    new VariantSampleMatrix(sampleIds, rdd, localMapFn, id => localSamplePredicate(id) && p(id))
  }

  def aggregateBySampleWithKeys[U](zeroValue: U)(
    seqOp: (U, Variant, Int, T) => U,
    combOp: (U, U) => U): Map[Int, U] = {
    val localMapFn = mapFn

    val filteredNums = (0 until nSamples).filter(samplePredicate)

    val numFilteredNum = Array.fill[Int](nSamples)(-1)
    for ((i, j) <- filteredNums.zipWithIndex)
      numFilteredNum(i) = j
    val numFilteredNumBroadcast = rdd.sparkContext.broadcast(numFilteredNum)

    val a = rdd.aggregate(Vector.fill[U](filteredNums.length)(zeroValue))({
      case (a, (v, gs)) => {
        gs
        .foldLeft(a)({
          case (acc, (s, g)) => {
            val j = numFilteredNumBroadcast.value(s)
            if (j != -1)
              acc.updated(j, seqOp(acc(j), v, s, localMapFn(v, s, g)))
            else
              acc
          }
        })
      }
    },
    (v1: Vector[U], v2: Vector[U]) => v1.zipWith(v2, combOp))

    filteredNums
    .zip(a)
    .toMap
  }

  def aggregateBySample[U](zeroValue: U)(
    seqOp: (U, T) => U,
    combOp: (U, U) => U): Map[Int, U] =
    aggregateBySampleWithKeys(zeroValue)((e, v, s, g) => seqOp(e, g), combOp)

  def aggregateByVariantWithKeys[U](zeroValue: U)(
    seqOp: (U, Variant, Int, T) => U,
    combOp: (U, U) => U)(implicit ut: ClassTag[U]): Map[Variant, U] = {
    val localSamplePredicate = samplePredicate
    val localMapFn = mapFn

    rdd.aggregateByKey(zeroValue)(
      (x, gs) => gs
                 .iterator
                 .filter({ case (s, g) => localSamplePredicate(s) })
                 .aggregate(x)({ case (x2, (s, g)) => seqOp(x2, gs.variant, s, localMapFn(gs.variant, s, g)) }, combOp),
      combOp)
    .collectAsMap()
  }

  def aggregateByVariant[U](zeroValue: U)(
    seqOp: (U, T) => U,
    combOp: (U, U) => U)(implicit ut: ClassTag[U]): Map[Variant, U] =
    aggregateByVariantWithKeys(zeroValue)((e, v, s, g) => seqOp(e, g), combOp)

  def foldBySample(zeroValue: T)(combOp: (T, T) => T): Map[Int, T] = {
    val localSamplePredicate = samplePredicate
    val localMapFn = mapFn

    val filteredNums = (0 until nSamples).filter(samplePredicate)
    val nFilteredNums = filteredNums.length
    println("nFilteredNums: " + nFilteredNums)

    val numFilteredNum = Array.fill[Int](nSamples)(-1)
    for ((i, j) <- filteredNums.zipWithIndex)
      numFilteredNum(i) = j
    val numFilteredNumBroadcast = rdd.sparkContext.broadcast(numFilteredNum)

    val vCombOp: (Vector[T], Vector[T]) => Vector[T] =
      (a1, a2) => a1.iterator.zip(a2.iterator).map(combOp.tupled).toVector
    val a = rdd
            .aggregate(Vector.fill[T](nFilteredNums)(zeroValue))({
      case (a, (v, gs)) => a.iterator.zip(gs.iterator
                                          .filter({ case (s, g) => localSamplePredicate(s) })
                                          .map({ case (s, g) => localMapFn(v, s, g) }))
                           .map(combOp.tupled)
                           .toVector
    },
            vCombOp)

    filteredNums.toVector.zip(a).toMap
  }

  def reduceByVariant(combOp: (T, T) => T)(implicit tt: ClassTag[T]): Map[Variant, T] = {
    val localSamplePredicate = samplePredicate
    val localMapFn = mapFn

    rdd
    .mapValues(gs => gs.iterator
                     .filter({ case (s, g) => localSamplePredicate(s) })
                     .map({ case (s, g) => localMapFn(gs.variant, s, g) })
                     .reduce(combOp))
    .reduceByKey(combOp)
    .collectAsMap()
  }

  def foldByVariant(zeroValue: T)(combOp: (T, T) => T)(implicit tt: ClassTag[T]): Map[Variant, T] = {
    val localSamplePredicate = samplePredicate
    val localMapFn = mapFn

    rdd
    .mapValues(gs => gs.iterator
                     .filter({ case (s, g) => localSamplePredicate(s) })
                     .map({ case (s, g) => localMapFn(gs.variant, s, g) })
                     .fold(zeroValue)(combOp))
    .foldByKey(zeroValue)(combOp)
    .collectAsMap()
  }
}