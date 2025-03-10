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

package org.apache.spark.sql.delta.schema

import java.util.Locale

import scala.util.control.NonFatal

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.{Resolver, TypeCoercion, UnresolvedAttribute}
import org.apache.spark.sql.catalyst.expressions.Literal
import org.apache.spark.sql.catalyst.plans.logical.DeltaMergeInto
import org.apache.spark.sql.catalyst.util.CaseInsensitiveMap
import org.apache.spark.sql.types.{ArrayType, ByteType, DataType, DecimalType, IntegerType, MapType, Metadata, NullType, ShortType, StructField, StructType}

/**
 * Utils to merge table schema with data schema.
 * This is split from SchemaUtils, because finalSchema is introduced into DeltaMergeInto,
 * and resolving the final schema is now part of [[DeltaMergeInto.resolveReferencesAndSchema]].
 */
object SchemaMergingUtils {

  val DELTA_COL_RESOLVER: (String, String) => Boolean =
    org.apache.spark.sql.catalyst.analysis.caseInsensitiveResolution
  /**
   * Returns all column names in this schema as a flat list. For example, a schema like:
   *   | - a
   *   | | - 1
   *   | | - 2
   *   | - b
   *   | - c
   *   | | - nest
   *   |   | - 3
   *   will get flattened to: "a", "a.1", "a.2", "b", "c", "c.nest", "c.nest.3"
   */
  def explodeNestedFieldNames(schema: StructType): Seq[String] = {
    def explode(schema: StructType): Seq[Seq[String]] = {
      def recurseIntoComplexTypes(complexType: DataType): Seq[Seq[String]] = {
        complexType match {
          case s: StructType => explode(s)
          case a: ArrayType => recurseIntoComplexTypes(a.elementType).map(Seq("element") ++ _)
          case m: MapType =>
            recurseIntoComplexTypes(m.keyType).map(Seq("key") ++ _) ++
              recurseIntoComplexTypes(m.valueType).map(Seq("value") ++ _)
          case _ => Nil
        }
      }

      schema.flatMap {
        case StructField(name, s: StructType, _, _) =>
          Seq(Seq(name)) ++ explode(s).map(nested => Seq(name) ++ nested)
        case StructField(name, a: ArrayType, _, _) =>
          Seq(Seq(name)) ++ recurseIntoComplexTypes(a).map(nested => Seq(name) ++ nested)
        case StructField(name, m: MapType, _, _) =>
          Seq(Seq(name)) ++ recurseIntoComplexTypes(m).map(nested => Seq(name) ++ nested)
        case f => Seq(f.name) :: Nil
      }
    }

    explode(schema).map(UnresolvedAttribute.apply(_).name)
  }

  /**
   * Checks if input column names have duplicate identifiers. This throws an exception if
   * the duplication exists.
   *
   * @param schema the schema to check for duplicates
   * @param colType column type name, used in an exception message
   */
  def checkColumnNameDuplication(schema: StructType, colType: String): Unit = {
    val columnNames = explodeNestedFieldNames(schema)
    // scalastyle:off caselocale
    val names = columnNames.map(_.toLowerCase)
    // scalastyle:on caselocale
    if (names.distinct.length != names.length) {
      val duplicateColumns = names.groupBy(identity).collect {
        case (x, ys) if ys.length > 1 => s"$x"
      }
      throw new AnalysisException(
        s"Found duplicate column(s) $colType: ${duplicateColumns.mkString(", ")}")
    }
  }

  /**
   * Check whether we can write to the Delta table, which has `tableSchema`, using a query that has
   * `dataSchema`. Our rules are that:
   *   - `dataSchema` may be missing columns or have additional columns
   *   - We don't trust the nullability in `dataSchema`. Assume fields are nullable.
   *   - We only allow nested StructType expansions. For all other complex types, we check for
   *     strict equality
   *   - `dataSchema` can't have duplicate column names. Columns that only differ by case are also
   *     not allowed.
   * The following merging strategy is
   * applied:
   *  - The name of the current field is used.
   *  - The data types are merged by calling this function.
   *  - We respect the current field's nullability.
   *  - The metadata is current field's metadata.
   *
   * Schema merging occurs in a case insensitive manner. Hence, column names that only differ
   * by case are not accepted in the `dataSchema`.
   *
   * @param tableSchema The current schema of the table.
   * @param dataSchema The schema of the new data being written.
   * @param allowImplicitConversions Whether to allow Spark SQL implicit conversions. By default,
   *                                 we merge according to Parquet write compatibility - for
   *                                 example, an integer type data field will throw when merged to a
   *                                 string type table field, because int and string aren't stored
   *                                 the same way in Parquet files. With this flag enabled, the
   *                                 merge will succeed, because once we get to write time Spark SQL
   *                                 will support implicitly converting the int to a string.
   * @param keepExistingType Whether to keep existing types instead of trying to merge types.
   * @param fixedTypeColumns The set of columns whose type should not be changed in any case.
   */
  def mergeSchemas(
      tableSchema: StructType,
      dataSchema: StructType,
      allowImplicitConversions: Boolean = false,
      keepExistingType: Boolean = false,
      fixedTypeColumns: Set[String] = Set.empty): StructType = {
    checkColumnNameDuplication(dataSchema, "in the data to save")
    def merge(
        current: DataType,
        update: DataType,
        fixedTypeColumnsSet: Set[String] = Set.empty): DataType = {
      (current, update) match {
        case (StructType(currentFields), StructType(updateFields)) =>
          // Merge existing fields.
          val updateFieldMap = toFieldMap(updateFields)
          val updatedCurrentFields = currentFields.map { currentField =>
            updateFieldMap.get(currentField.name) match {
              case Some(updateField) =>
                if (fixedTypeColumnsSet.contains(currentField.name.toLowerCase(Locale.ROOT)) &&
                    !equalsIgnoreCaseAndCompatibleNullability(
                      currentField.dataType, updateField.dataType)) {
                  throw new AnalysisException(
                    s"Column ${currentField.name} is a generated column " +
                      "or a column used by a generated column. " +
                      s"The data type is ${currentField.dataType.sql}. " +
                      s"It doesn't accept data type ${updateField.dataType.sql}")
                }
                try {
                  StructField(
                    currentField.name,
                    merge(currentField.dataType, updateField.dataType),
                    currentField.nullable,
                    currentField.metadata)
                } catch {
                  case NonFatal(e) =>
                    throw new AnalysisException(s"Failed to merge fields '${currentField.name}' " +
                        s"and '${updateField.name}'. " + e.getMessage)
                }
              case None =>
                // Retain the old field.
                currentField
            }
          }

          // Identify the newly added fields.
          val nameToFieldMap = toFieldMap(currentFields)
          val newFields = updateFields.filterNot(f => nameToFieldMap.contains(f.name))

          // Create the merged struct, the new fields are appended at the end of the struct.
          StructType(updatedCurrentFields ++ newFields)
        case (ArrayType(currentElementType, currentContainsNull),
              ArrayType(updateElementType, _)) =>
          ArrayType(
            merge(currentElementType, updateElementType),
            currentContainsNull)
        case (MapType(currentKeyType, currentElementType, currentContainsNull),
              MapType(updateKeyType, updateElementType, _)) =>
          MapType(
            merge(currentKeyType, updateKeyType),
            merge(currentElementType, updateElementType),
            currentContainsNull)

        // Simply keeps the existing type for primitive types
        case (current, update) if keepExistingType => current

        // If implicit conversions are allowed, that means we can use any valid implicit cast to
        // perform the merge.
        case (current, update)
            if allowImplicitConversions && typeForImplicitCast(update, current).isDefined =>
          typeForImplicitCast(update, current).get

        case (DecimalType.Fixed(leftPrecision, leftScale),
              DecimalType.Fixed(rightPrecision, rightScale)) =>
          if ((leftPrecision == rightPrecision) && (leftScale == rightScale)) {
            current
          } else if ((leftPrecision != rightPrecision) && (leftScale != rightScale)) {
            throw new AnalysisException("Failed to merge decimal types with incompatible " +
              s"precision $leftPrecision and $rightPrecision & scale $leftScale and $rightScale")
          } else if (leftPrecision != rightPrecision) {
            throw new AnalysisException("Failed to merge decimal types with incompatible " +
              s"precision $leftPrecision and $rightPrecision")
          } else {
            throw new AnalysisException("Failed to merge decimal types with incompatible " +
              s"scale $leftScale and $rightScale")
          }
        case _ if current == update =>
          current

        // Parquet physically stores ByteType, ShortType and IntType as IntType, so when a parquet
        // column is of one of these three types, you can read this column as any of these three
        // types. Since Parquet doesn't complain, we should also allow upcasting among these
        // three types when merging schemas.
        case (ByteType, ShortType) => ShortType
        case (ByteType, IntegerType) => IntegerType

        case (ShortType, ByteType) => ShortType
        case (ShortType, IntegerType) => IntegerType

        case (IntegerType, ShortType) => IntegerType
        case (IntegerType, ByteType) => IntegerType

        case (NullType, _) =>
          update
        case (_, NullType) =>
          current
        case _ =>
          throw new AnalysisException(
            s"Failed to merge incompatible data types $current and $update")
      }
    }
    merge(tableSchema, dataSchema, fixedTypeColumns.map(_.toLowerCase(Locale.ROOT)))
      .asInstanceOf[StructType]
  }

  /**
   * Try to cast the source data type to the target type, returning the final type or None if
   * there's no valid cast.
   */
  private def typeForImplicitCast(sourceType: DataType, targetType: DataType): Option[DataType] = {
    TypeCoercion.ImplicitTypeCasts.implicitCast(Literal.default(sourceType), targetType)
      .map(_.dataType)
  }

  def toFieldMap(fields: Seq[StructField]): Map[String, StructField] = {
    CaseInsensitiveMap(fields.map(field => field.name -> field).toMap)
  }

  /**
   * Transform (nested) columns in a schema.
   *
   * @param schema to transform.
   * @param tf function to apply.
   * @return the transformed schema.
   */
  def transformColumns(
      schema: StructType)(
      tf: (Seq[String], StructField, Resolver) => StructField): StructType = {
    def transform[E <: DataType](path: Seq[String], dt: E): E = {
      val newDt = dt match {
        case StructType(fields) =>
          StructType(fields.map { field =>
            val newField = tf(path, field, DELTA_COL_RESOLVER)
            // maintain the old name as we recurse into the subfields
            newField.copy(dataType = transform(path :+ field.name, newField.dataType))
          })
        case ArrayType(elementType, containsNull) =>
          ArrayType(transform(path :+ "element", elementType), containsNull)
        case MapType(keyType, valueType, valueContainsNull) =>
          MapType(
            transform(path :+ "key", keyType),
            transform(path :+ "value", valueType),
            valueContainsNull)
        case other => other
      }
      newDt.asInstanceOf[E]
    }
    transform(Seq.empty, schema)
  }

  /**
   *
   * Taken from DataType
   *
   * Compares two types, ignoring compatible nullability of ArrayType, MapType, StructType, and
   * ignoring case sensitivity of field names in StructType.
   *
   * Compatible nullability is defined as follows:
   *   - If `from` and `to` are ArrayTypes, `from` has a compatible nullability with `to`
   *   if and only if `to.containsNull` is true, or both of `from.containsNull` and
   *   `to.containsNull` are false.
   *   - If `from` and `to` are MapTypes, `from` has a compatible nullability with `to`
   *   if and only if `to.valueContainsNull` is true, or both of `from.valueContainsNull` and
   *   `to.valueContainsNull` are false.
   *   - If `from` and `to` are StructTypes, `from` has a compatible nullability with `to`
   *   if and only if for all every pair of fields, `to.nullable` is true, or both
   *   of `fromField.nullable` and `toField.nullable` are false.
   */
  def equalsIgnoreCaseAndCompatibleNullability(from: DataType, to: DataType): Boolean = {
    (from, to) match {
      case (ArrayType(fromElement, fn), ArrayType(toElement, tn)) =>
        (tn || !fn) && equalsIgnoreCaseAndCompatibleNullability(fromElement, toElement)

      case (MapType(fromKey, fromValue, fn), MapType(toKey, toValue, tn)) =>
        (tn || !fn) &&
          equalsIgnoreCaseAndCompatibleNullability(fromKey, toKey) &&
          equalsIgnoreCaseAndCompatibleNullability(fromValue, toValue)

      case (StructType(fromFields), StructType(toFields)) =>
        fromFields.length == toFields.length &&
          fromFields.zip(toFields).forall { case (fromField, toField) =>
            fromField.name.equalsIgnoreCase(toField.name) &&
              (toField.nullable || !fromField.nullable) &&
              equalsIgnoreCaseAndCompatibleNullability(fromField.dataType, toField.dataType)
          }

      case (fromDataType, toDataType) => fromDataType == toDataType
    }
  }
}
