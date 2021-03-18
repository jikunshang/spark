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

package org.apache.spark.sql.execution.benchmark

import org.apache.spark.SparkConf
import org.apache.spark.benchmark.Benchmark
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.catalog.HiveTableRelation
import org.apache.spark.sql.catalyst.plans.logical.SubqueryAlias
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.execution.datasources.LogicalRelation

/**
 * Benchmark to measure TPCDS query performance.
 * To run this:
 * {{{
 *   1. without sbt:
 *        bin/spark-submit --class <this class> <spark sql test jar> --data-location <location>
 *   2. build/sbt "sql/test:runMain <this class> --data-location <TPCDS data location>"
 *   3. generate result: SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt
 *        "sql/test:runMain <this class> --data-location <location>"
 *      Results will be written to "benchmarks/TPCDSQueryBenchmark-results.txt".
 * }}}
 */
object TPCHQueryBenchmark extends SqlBasedBenchmark {

  override def getSparkSession: SparkSession = {
    val conf = new SparkConf()
      .set("spark.sql.parquet.compression.codec", "snappy")
      .set("spark.sql.crossJoin.enabled", "true")

    SparkSession.builder.config(conf).getOrCreate()
  }

  val tables = Seq("customer", "lineitem", "nation", "orders",
    "part", "partsupp", "region", "supplier")

  def setupTables(dataLocation: String): Map[String, Long] = {
    tables.map { tableName =>
      spark.read.parquet(s"$dataLocation/$tableName").createOrReplaceTempView(tableName)
      tableName -> spark.table(tableName).count()
    }.toMap
  }

  def runTpchQueries(
      queryLocation: String,
      queries: Seq[String],
      tableSizes: Map[String, Long],
      nameSuffix: String = ""): Unit = {
    queries.foreach { name =>
      val queryString = resourceToString(s"$queryLocation/$name.sql",
        classLoader = Thread.currentThread().getContextClassLoader)

      // This is an indirect hack to estimate the size of each query's input by traversing the
      // logical plan and adding up the sizes of all tables that appear in the plan.
      val queryRelations = scala.collection.mutable.HashSet[String]()
      spark.sql(queryString).queryExecution.analyzed.foreach {
        case SubqueryAlias(alias, _: LogicalRelation) =>
          queryRelations.add(alias.name)
        case LogicalRelation(_, _, Some(catalogTable), _) =>
          queryRelations.add(catalogTable.identifier.table)
        case HiveTableRelation(tableMeta, _, _, _, _) =>
          queryRelations.add(tableMeta.identifier.table)
        case _ =>
      }
      val numRows = queryRelations.map(tableSizes.getOrElse(_, 0L)).sum
      val benchmark = new Benchmark(s"TPCH Snappy", numRows, 2,
        output = output, outputPerIteration = true)
      benchmark.addCase(s"$name$nameSuffix") { _ =>
        spark.sql(queryString).noop()
      }
      benchmark.run()
    }
  }

  def filterQueries(
      origQueries: Seq[String],
      args: TPCDSQueryBenchmarkArguments): Seq[String] = {
    if (args.queryFilter.nonEmpty) {
      origQueries.filter(args.queryFilter.contains)
    } else {
      origQueries
    }
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    val benchmarkArgs = new TPCDSQueryBenchmarkArguments(mainArgs)

    // List of all TPC-DS v1.4 queries
    val tpchQueries = Seq(
      "q1", "q2", "q3", "q4", "q5", "q6", "q7", "q8", "q9", "q10",
      "q11", "q12", "q13", "q14", "q15", "q16", "q17", "q18", "q19", "q20",
      "q21", "q22")

    // If `--query-filter` defined, filters the queries that this option selects
    val queriesToRun = filterQueries(tpchQueries, benchmarkArgs)

    if ((queriesToRun).isEmpty) {
      throw new RuntimeException(
        s"Empty queries to run. Bad query name filter: ${benchmarkArgs.queryFilter}")
    }

    val tableSizes = setupTables(benchmarkArgs.dataLocation)
    runTpchQueries(queryLocation = "tpch", queries = queriesToRun, tableSizes)

  }
}
