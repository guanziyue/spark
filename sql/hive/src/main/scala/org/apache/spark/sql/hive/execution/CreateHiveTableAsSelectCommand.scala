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

package org.apache.spark.sql.hive.execution

import scala.util.control.NonFatal

import org.apache.spark.sql.{Row, SaveMode, SparkSession}
import org.apache.spark.sql.catalyst.catalog.{CatalogTable, SessionCatalog}
import org.apache.spark.sql.catalyst.expressions.SortOrder
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.util.CharVarcharUtils
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.command.{DataWritingCommand, DDLUtils}
import org.apache.spark.sql.execution.datasources.{HadoopFsRelation, InsertIntoHadoopFsRelationCommand, LogicalRelation, V1WriteCommand, V1WritesUtils}
import org.apache.spark.sql.hive.HiveSessionCatalog
import org.apache.spark.util.Utils

trait CreateHiveTableAsSelectBase extends V1WriteCommand with V1WritesHiveUtils {
  val tableDesc: CatalogTable
  val query: LogicalPlan
  val outputColumnNames: Seq[String]
  val mode: SaveMode

  protected val tableIdentifier = tableDesc.identifier

  override def requiredOrdering: Seq[SortOrder] = {
    // If the table does not exist the schema should always be empty.
    val table = if (tableDesc.schema.isEmpty) {
      val tableSchema = CharVarcharUtils.getRawSchema(outputColumns.toStructType, conf)
      tableDesc.copy(schema = tableSchema)
    } else {
      tableDesc
    }
    // For CTAS, there is no static partition values to insert.
    val partition = tableDesc.partitionColumnNames.map(_ -> None).toMap
    val partitionColumns = getDynamicPartitionColumns(table, partition, query)
    val options = getOptionsWithHiveBucketWrite(tableDesc.bucketSpec)
    V1WritesUtils.getSortOrder(outputColumns, partitionColumns, tableDesc.bucketSpec, options)
  }

  override def run(sparkSession: SparkSession, child: SparkPlan): Seq[Row] = {
    val catalog = sparkSession.sessionState.catalog
    val tableExists = catalog.tableExists(tableIdentifier)

    if (tableExists) {
      assert(mode != SaveMode.Overwrite,
        s"Expect the table $tableIdentifier has been dropped when the save mode is Overwrite")

      if (mode == SaveMode.ErrorIfExists) {
        throw QueryCompilationErrors.tableIdentifierExistsError(tableIdentifier)
      }
      if (mode == SaveMode.Ignore) {
        // Since the table already exists and the save mode is Ignore, we will just return.
        return Seq.empty
      }

      val command = getWritingCommand(catalog, tableDesc, tableExists = true)
      command.run(sparkSession, child)
      DataWritingCommand.propogateMetrics(sparkSession.sparkContext, command, metrics)
    } else {
        tableDesc.storage.locationUri.foreach { p =>
          DataWritingCommand.assertEmptyRootPath(p, mode, sparkSession.sessionState.newHadoopConf)
        }
      // TODO ideally, we should get the output data ready first and then
      // add the relation into catalog, just in case of failure occurs while data
      // processing.
      val tableSchema = CharVarcharUtils.getRawSchema(
        outputColumns.toStructType, sparkSession.sessionState.conf)
      assert(tableDesc.schema.isEmpty)
      catalog.createTable(
        tableDesc.copy(schema = tableSchema), ignoreIfExists = false)

      try {
        // Read back the metadata of the table which was created just now.
        val createdTableMeta = catalog.getTableMetadata(tableDesc.identifier)
        val command = getWritingCommand(catalog, createdTableMeta, tableExists = false)
        command.run(sparkSession, child)
        DataWritingCommand.propogateMetrics(sparkSession.sparkContext, command, metrics)
      } catch {
        case NonFatal(e) =>
          // drop the created table.
          catalog.dropTable(tableIdentifier, ignoreIfNotExists = true, purge = false)
          throw e
      }
    }

    Seq.empty[Row]
  }

  // Returns `DataWritingCommand` which actually writes data into the table.
  def getWritingCommand(
    catalog: SessionCatalog,
    tableDesc: CatalogTable,
    tableExists: Boolean): DataWritingCommand

  // A subclass should override this with the Class name of the concrete type expected to be
  // returned from `getWritingCommand`.
  def writingCommandClassName: String

  override def argString(maxFields: Int): String = {
    s"[Database: ${tableDesc.database}, " +
    s"TableName: ${tableDesc.identifier.table}, " +
    s"${writingCommandClassName}]"
  }
}

/**
 * Create table and insert the query result into it.
 *
 * @param tableDesc the table description, which may contain serde, storage handler etc.
 * @param query the query whose result will be insert into the new relation
 * @param mode SaveMode
 */
case class CreateHiveTableAsSelectCommand(
    tableDesc: CatalogTable,
    query: LogicalPlan,
    outputColumnNames: Seq[String],
    mode: SaveMode)
  extends CreateHiveTableAsSelectBase {

  override def getWritingCommand(
      catalog: SessionCatalog,
      tableDesc: CatalogTable,
      tableExists: Boolean): DataWritingCommand = {
    // For CTAS, there is no static partition values to insert.
    val partition = tableDesc.partitionColumnNames.map(_ -> None).toMap
    InsertIntoHiveTable(
      tableDesc,
      partition,
      query,
      overwrite = if (tableExists) false else true,
      ifPartitionNotExists = false,
      outputColumnNames = outputColumnNames)
  }

  override def writingCommandClassName: String =
    Utils.getSimpleName(classOf[InsertIntoHiveTable])

  override protected def withNewChildInternal(
    newChild: LogicalPlan): CreateHiveTableAsSelectCommand = copy(query = newChild)
}

/**
 * Create table and insert the query result into it. This creates Hive table but inserts
 * the query result into it by using data source.
 *
 * @param tableDesc the table description, which may contain serde, storage handler etc.
 * @param query the query whose result will be insert into the new relation
 * @param mode SaveMode
 */
case class OptimizedCreateHiveTableAsSelectCommand(
    tableDesc: CatalogTable,
    query: LogicalPlan,
    outputColumnNames: Seq[String],
    mode: SaveMode)
  extends CreateHiveTableAsSelectBase {

  override def getWritingCommand(
      catalog: SessionCatalog,
      tableDesc: CatalogTable,
      tableExists: Boolean): DataWritingCommand = {
    val metastoreCatalog = catalog.asInstanceOf[HiveSessionCatalog].metastoreCatalog
    val hiveTable = DDLUtils.readHiveTable(tableDesc)

    val hadoopRelation = metastoreCatalog.convert(hiveTable, isWrite = true) match {
      case LogicalRelation(t: HadoopFsRelation, _, _, _) => t
      case _ => throw QueryCompilationErrors.tableIdentifierNotConvertedToHadoopFsRelationError(
        tableIdentifier)
    }

    InsertIntoHadoopFsRelationCommand(
      hadoopRelation.location.rootPaths.head,
      Map.empty, // We don't support to convert partitioned table.
      false,
      Seq.empty, // We don't support to convert partitioned table.
      hadoopRelation.bucketSpec,
      hadoopRelation.fileFormat,
      hadoopRelation.options,
      query,
      if (tableExists) mode else SaveMode.Overwrite,
      Some(tableDesc),
      Some(hadoopRelation.location),
      query.output.map(_.name))
  }

  override def writingCommandClassName: String =
    Utils.getSimpleName(classOf[InsertIntoHadoopFsRelationCommand])

  override protected def withNewChildInternal(
    newChild: LogicalPlan): OptimizedCreateHiveTableAsSelectCommand = copy(query = newChild)
}
