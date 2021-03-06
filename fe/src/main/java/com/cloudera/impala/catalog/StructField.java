// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.catalog;

import org.apache.hadoop.hive.metastore.MetaStoreUtils;

import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.thrift.TColumnType;
import com.cloudera.impala.thrift.TStructField;
import com.cloudera.impala.thrift.TTypeNode;

/**
 * TODO: Support comments for struct fields. The Metastore does not properly store
 * comments of struct fields. We set comment_ to null to avoid compatibility issues.
 */
public class StructField {
  protected final String name_;
  protected Type type_;
  protected String comment_;

  public StructField(String name, Type type, String comment) {
    name_ = name;
    type_ = type;
    comment_ = comment;
  }

  public void analyze() throws AnalysisException {
    // Check whether the column name meets the Metastore's requirements.
    if (!MetaStoreUtils.validateName(name_)) {
      throw new AnalysisException("Invalid struct field name: " + name_);
    }
    type_.analyze();
  }

  public String getComment() { return comment_; }
  public String getName() { return name_; }
  public Type getType() { return type_; }

  public String toSql() {
    StringBuilder sb = new StringBuilder(name_);
    if (type_ != null) sb.append(":" + type_.toSql());
    if (comment_ != null) sb.append(String.format(" COMMENT '%s'", comment_));
    return sb.toString();
  }

  public void toThrift(TColumnType container, TTypeNode node) {
    TStructField field = new TStructField();
    field.setName(name_);
    if (comment_ != null) field.setComment(comment_);
    node.struct_fields.add(field);
    type_.toThrift(container);
  }
}
