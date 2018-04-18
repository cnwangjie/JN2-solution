package FD

import collection.mutable._
import util.control.Breaks._
import org.joda.time.DateTime
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._

import java.util.concurrent.Future
import java.util.concurrent.Callable
import java.util.concurrent.Executors

object Main {

  private val conf = new SparkConf().setAppName("AR")
    .set("spark.blacklist.enabled", "true")
    .set("spark.blacklist.timeout", "60s")
    .set("spark.cores.max", "168")
    .set("spark.driver.cores", "23")
    .set("spark.driver.maxResultSize", "2G")
    .set("spark.executor.cores", "23")
    .set("spark.executor.extraJavaOptions", "-XX:+UseG1GC")
    .set("spark.network.timeout", "300s")
    .set("spark.speculation", "true")
  private val sc = new SparkContext(conf)
  private var data: RDD[Array[String]] = sc.emptyRDD

  private var attrNum: Int = 0

  private val dep: Map[String, Set[Int]] = Map()
  private val beDep: Map[Int, Set[String]] = Map()
  private val beNotDep: Map[Int, Set[Set[Int]]] = Map()

  private val maxLHSsum = 6

  def combination(n: Int) = Range(0, if (n > maxLHSsum) maxLHSsum else n).flatMap(List.range(0, n).combinations(_))

  def compareDepRel(p: List[Int], r: Int): Boolean = {
    if (p.length == 0) {
      val fact = data.first()(r)
      return data.filter(_(r) != fact).count == 0
    }

    return data.map(item => (p.map(item(_)).mkString("|"), item(r)))
      .groupByKey()
      .values
      .filter(_.size > 1)
      .filter(i => !i.forall(_ == i.head))
      .count == 0
  }

  def main(args: Array[String]) {
    println("===================================")
    println("         initialized")
    println("===================================")

    sc.setLogLevel("ERROR")
    val inputFilePath = if (args.length > 0) args(0) else "file:///home/wangjie/Workspace/seu/dataset/test/bots_200_10.csv"
    val outputFilePath = if (args.length > 1) args(1) else "file:///home/wangjie/Workspace/seu/200_10.ans.byspark"
    val inputFile = sc.textFile(inputFilePath, 45)
    val dataSize = inputFile.count
    println("===================================")
    println("         data loaded")
    println("===================================")
    println("data size: " + dataSize)
    data = inputFile.map(_.split(",")).cache()
    attrNum = data.first().length

    println("===================================")
    println("         data handled")
    println("===================================")
    println("num of attrbutes: " + attrNum)

    val startTime = DateTime.now.getMillis
    var lastLogTime: Long = 0
    var cycleTimes = 0
    val indexCombinations = combination(attrNum)
    val allCycleTimes = indexCombinations.length
    var beDroppedCase = 0
    indexCombinations.foreach(r => {
      cycleTimes += 1
      val curTime = DateTime.now.getMillis
      if (curTime - lastLogTime > 1000) {
        println("progress: " + (cycleTimes.toDouble / allCycleTimes * 100).formatted("%.2f") +
        "% (" + cycleTimes + "/" + allCycleTimes + ") spend time: " + (DateTime.now.getMillis - startTime) / 1000 +
        "s | calulating attributions sum: " + r.size +
        " dropped cases: " + beDroppedCase +
        " (" + (beDroppedCase.toDouble / allCycleTimes.toDouble * 10).formatted("%.2f") + "%)")
        lastLogTime = curTime
      }
      var toCompare: List[Tuple2[List[Int], Int]] = List()
      for (i <- 0 to attrNum - 1) {

        if (!r.contains(i)) {
          var isMinimal = true

          var isUseful = true
          // if (beNotDep.contains(i)) {
          //   beNotDep(i).foreach(notDepedSet => {
          //     if (r.toSet.subsetOf(notDepedSet)) {
          //       isUseful = false
          //     }
          //   })
          // }

          if (beDep.contains(i)) {

            if (beDep(i).contains("")) isMinimal = false

            beDep(i).foreach(key => {
              if (isMinimal) {
                val childSet = key.split(",").map(_.toInt)

                var isChildSet = true
                for (j <- 0 to childSet.length - 1) {

                  if (!r.contains(childSet(j))) {
                    isChildSet = false
                  }
                }
                if (isChildSet) isMinimal = false
              }
            })
          }

          if (isMinimal && isUseful) {
            toCompare = toCompare :+ (r, i)
            // if (compareDepRel(r, i)) {
            //   val strR = r.mkString(",")
            //   if (dep.contains(strR)) {
            //     dep(strR) += i
            //   } else {
            //     dep(strR) = Set(i)
            //   }

            //   if (beDep.contains(i)) {
            //     beDep(i) += strR
            //   } else {
            //     beDep(i) = Set(strR)
            //   }
            // } else {
            //   if (beNotDep.contains(i)) {
            //     beNotDep(i) += Set() ++ r.toSet
            //   } else {
            //     beNotDep(i) = Set(Set() ++ r.toSet)
            //   }
            // }
          } else {
            beDroppedCase += 1
          }
        }

      }
      // ------ end of mapping attributes
      val result = new Array[Future[Boolean]](toCompare.size)
      val executors = Executors.newFixedThreadPool(toCompare.size)
      for (i <- 0 until toCompare.size) {
        val task = executors.submit(new Callable[Boolean] {
          override def call(): Boolean = {
            return compareDepRel(toCompare(i)._1, toCompare(i)._2)
          }
        })
        result(i) = task
      }

      for (i <- 0 until toCompare.size) {
        if (result(i).get()) {
          val strR = toCompare(i)._1.mkString(",")
          if (dep.contains(strR)) {
            dep(strR) += toCompare(i)._2
          } else {
            dep(strR) = Set(toCompare(i)._2)
          }

          if (beDep.contains(toCompare(i)._2)) {
            beDep(toCompare(i)._2) += strR
          } else {
            beDep(toCompare(i)._2) = Set(strR)
          }
        }
      }

      executors.shutdownNow
    })

    println("===================================")
    println("          calculated")
    println("===================================")
    println("time spent:" + (DateTime.now.getMillis - startTime) / 1000 + "s")
    println("result count: " + dep.size)
    println("dropped cases: " + beDroppedCase +
      " (" + (beDroppedCase.toDouble / allCycleTimes.toDouble * 10).formatted("%.2f") + "%)")

    val result: Set[String] = Set()
    dep.keys.foreach(key => {
      val ls = if (key == "") "" else key.split(",")
        .map(_.toInt)
        .map(_ + 1)
        .sortWith(_ < _)
        .map("column" + _)
        .mkString(",")
      val rs = dep(key)
        .map(_ + 1)
        .toList
        .sortWith(_ < _)
        .map("column" + _)
        .mkString(",")

      result += "[" + ls + "]:" + rs
    })
    // result.toList.foreach(println(_))
    sc.parallelize(result.toList, 1).saveAsTextFile(outputFilePath)

    println("===================================")
    println("             done")
    println("===================================")
  }

}
