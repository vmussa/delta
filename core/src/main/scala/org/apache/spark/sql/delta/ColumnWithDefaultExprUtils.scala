/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

// scalastyle:off import.ordering.noEmptyLine
import scala.collection.mutable

import org.apache.spark.sql.delta.actions.Protocol
import org.apache.spark.sql.delta.constraints.{Constraint, Constraints}
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.schema.SchemaUtils

import org.apache.spark.sql.{Column, DataFrame, Dataset, Encoder}
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.catalyst.expressions.EqualNullSafe
import org.apache.spark.sql.catalyst.util.CaseInsensitiveMap
import org.apache.spark.sql.execution.QueryExecution
import org.apache.spark.sql.execution.streaming.IncrementalExecution
import org.apache.spark.sql.types.{StructField, StructType}

/**
 * Provide utilities to handle columns with default expressions.
 */
object ColumnWithDefaultExprUtils extends DeltaLogging {

  // Return true if the column `col` has default expressions (and can thus be omitted from the
  // insertion list).
  def columnHasDefaultExpr(protocol: Protocol, col: StructField): Boolean = {
    GeneratedColumn.isGeneratedColumn(protocol, col)
  }

  /**
   * If there are columns with default expressions in `schema`, add a new project to generate
   * those columns missing in the schema, and return constraints for generated columns existing in
   * the schema.
   *
   * @param deltaLog The table's [[DeltaLog]] used for logging.
   * @param queryExecution Used to check whether the original query is a streaming query or not.
   * @param schema Table schema.
   * @param data The data to be written into the table.
   * @return The data with potentially additional default expressions projected and constraints
   *         from generated columns if any.
   */
  def addDefaultExprsOrReturnConstraints(
      deltaLog: DeltaLog,
      queryExecution: QueryExecution,
      schema: StructType,
      data: DataFrame): (DataFrame, Seq[Constraint]) = {
    val topLevelOutputNames = CaseInsensitiveMap(data.schema.map(f => f.name -> f).toMap)
    lazy val metadataOutputNames = CaseInsensitiveMap(schema.map(f => f.name -> f).toMap)
    val constraints = mutable.ArrayBuffer[Constraint]()
    var selectExprs = schema.map { f =>
      GeneratedColumn.getGenerationExpression(f) match {
        case Some(expr) =>
          if (topLevelOutputNames.contains(f.name)) {
            val column = SchemaUtils.fieldToColumn(f)
            // Add a constraint to make sure the value provided by the user is the same as the value
            // calculated by the generation expression.
            constraints += Constraints.Check(s"Generated Column", EqualNullSafe(column.expr, expr))
            column.alias(f.name)
          } else {
            new Column(expr).alias(f.name)
          }
        case None =>
          SchemaUtils.fieldToColumn(f).alias(f.name)
      }
    }
    val newData = queryExecution match {
      case incrementalExecution: IncrementalExecution =>
        selectFromStreamingDataFrame(incrementalExecution, data, selectExprs: _*)
      case _ => data.select(selectExprs: _*)
    }
    recordDeltaEvent(deltaLog, "delta.generatedColumns.write")
    (newData, constraints)
  }

  /**
   * Select `cols` from a micro batch DataFrame. Directly calling `select` won't work because it
   * will create a `QueryExecution` rather than inheriting `IncrementalExecution` from
   * the micro batch DataFrame. A streaming micro batch DataFrame to execute should use
   * `IncrementalExecution`.
   */
  private def selectFromStreamingDataFrame(
      incrementalExecution: IncrementalExecution,
      df: DataFrame,
      cols: Column*): DataFrame = {
    val newMicroBatch = df.select(cols: _*)
    val newIncrementalExecution = new IncrementalExecution(
      newMicroBatch.sparkSession,
      newMicroBatch.queryExecution.logical,
      incrementalExecution.outputMode,
      incrementalExecution.checkpointLocation,
      incrementalExecution.queryId,
      incrementalExecution.runId,
      incrementalExecution.currentBatchId,
      incrementalExecution.offsetSeqMetadata
    )
    newIncrementalExecution.executedPlan // Force the lazy generation of execution plan
    // Use reflection to call the private constructor.
    val constructor =
      classOf[Dataset[_]].getConstructor(classOf[QueryExecution], classOf[Encoder[_]])
    constructor.newInstance(
      newIncrementalExecution,
      RowEncoder(newIncrementalExecution.analyzed.schema)).asInstanceOf[DataFrame]
  }
}
