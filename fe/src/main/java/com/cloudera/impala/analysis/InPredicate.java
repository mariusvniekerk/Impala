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

package com.cloudera.impala.analysis;

import java.util.ArrayList;
import java.util.List;

import com.cloudera.impala.analysis.BinaryPredicate.Operator;
import com.cloudera.impala.catalog.Db;
import com.cloudera.impala.catalog.Function.CompareMode;
import com.cloudera.impala.catalog.ScalarFunction;
import com.cloudera.impala.catalog.Type;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.common.Reference;
import com.cloudera.impala.thrift.TExprNode;
import com.cloudera.impala.thrift.TExprNodeType;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.Lists;

/**
 * Class representing a [NOT] IN predicate. It determines if a specified value
 * (first child) matches any value in a subquery (second child) or a list
 * of values (remaining children).
 */
public class InPredicate extends Predicate {
  private static final String IN = "in";
  private static final String NOT_IN = "not_in";
  private final boolean isNotIn_;

  public boolean isNotIn() { return isNotIn_; }

  public static void initBuiltins(Db db) {
    for (Type t: Type.getSupportedTypes()) {
      if (t.isNull()) continue;
      db.addBuiltin(ScalarFunction.createBuiltin(IN, "impala::InPredicate::In",
          Lists.newArrayList(t, t), true, Type.BOOLEAN, false));
      db.addBuiltin(ScalarFunction.createBuiltin(NOT_IN, "impala::InPredicate::NotIn",
          Lists.newArrayList(t, t), true, Type.BOOLEAN, false));
    }
  }

  // First child is the comparison expr for which we
  // should check membership in the inList (the remaining children).
  public InPredicate(Expr compareExpr, List<Expr> inList, boolean isNotIn) {
    children_.add(compareExpr);
    children_.addAll(inList);
    isNotIn_ = isNotIn;
  }

  // C'tor for initializing an [NOT] IN predicate with a subquery child.
  public InPredicate(Expr compareExpr, Expr subquery, boolean isNotIn) {
    Preconditions.checkNotNull(compareExpr);
    Preconditions.checkNotNull(subquery);
    children_.add(compareExpr);
    children_.add(subquery);
    isNotIn_ = isNotIn;
  }

  /**
   * Copy c'tor used in clone().
   */
  protected InPredicate(InPredicate other) {
    super(other);
    isNotIn_ = other.isNotIn_;
  }

  @Override
  public void analyze(Analyzer analyzer) throws AnalysisException {
    if (isAnalyzed_) return;
    super.analyze(analyzer);

    if (contains(Subquery.class)) {
      // An [NOT] IN predicate with a subquery must contain two children, the second of
      // which is a Subquery.
      if (children_.size() != 2 || !(getChild(1) instanceof Subquery)) {
        throw new AnalysisException("Unsupported IN predicate with a subquery: " +
            toSqlImpl());
      }
      Subquery subquery = (Subquery)getChild(1);
      if (!subquery.returnsScalarColumn()) {
        throw new AnalysisException("Subquery must return a single column: " +
            subquery.toSql());
      }

      // Ensure that the column in the lhs of the IN predicate and the result of
      // the subquery are type compatible. No need to perform any
      // casting at this point. Any casting needed will be performed when the
      // subquery is unnested.
      ArrayList<Expr> subqueryExprs = subquery.getStatement().getResultExprs();
      Expr compareExpr = children_.get(0);
      Expr subqueryExpr = subqueryExprs.get(0);
      analyzer.getCompatibleType(compareExpr.getType(), compareExpr, subqueryExpr);
    } else {
      Preconditions.checkState(getChildren().size() >= 2);
      analyzer.castAllToCompatibleType(children_);
      if (children_.get(0).getType().isNull()) {
        // Make sure the BE never sees TYPE_NULL by picking an arbitrary type
        for (int i = 0; i < children_.size(); ++i) {
          uncheckedCastChild(Type.BOOLEAN, i);
        }
      }

      // Only lookup fn_ if all subqueries have been rewritten. If the second child is a
      // subquery, it will have type ArrayType, which cannot be resolved to a builtin
      // function and will fail analysis.
      Type[] argTypes = {getChild(0).type_, getChild(1).type_};
      if (isNotIn_) {
        fn_ = getBuiltinFunction(analyzer, NOT_IN, argTypes, CompareMode.IS_SUPERTYPE_OF);
      } else {
        fn_ = getBuiltinFunction(analyzer, IN, argTypes, CompareMode.IS_SUPERTYPE_OF);
      }
      Preconditions.checkNotNull(fn_);
      Preconditions.checkState(fn_.getReturnType().isBoolean());
      castForFunctionCall(false);
    }

    // TODO: Fix selectivity_ for nested predicate
    Reference<SlotRef> slotRefRef = new Reference<SlotRef>();
    Reference<Integer> idxRef = new Reference<Integer>();
    if (isSingleColumnPredicate(slotRefRef, idxRef)
        && idxRef.getRef() == 0
        && slotRefRef.getRef().getNumDistinctValues() > 0) {
      selectivity_ = (double) (getChildren().size() - 1)
          / (double) slotRefRef.getRef().getNumDistinctValues();
      selectivity_ = Math.max(0.0, Math.min(1.0, selectivity_));
    } else {
      selectivity_ = Expr.DEFAULT_SELECTIVITY;
    }
  }

  @Override
  protected void toThrift(TExprNode msg) {
    // Can't serialize a predicate with a subquery
    Preconditions.checkState(!contains(Subquery.class));
    msg.node_type = TExprNodeType.FUNCTION_CALL;
  }

  @Override
  public String toSqlImpl() {
    StringBuilder strBuilder = new StringBuilder();
    String notStr = (isNotIn_) ? "NOT " : "";
    strBuilder.append(getChild(0).toSql() + " " + notStr + "IN ");
    boolean hasSubquery = contains(Subquery.class);
    if (!hasSubquery) strBuilder.append("(");
    for (int i = 1; i < children_.size(); ++i) {
      strBuilder.append(getChild(i).toSql());
      strBuilder.append((i+1 != children_.size()) ? ", " : "");
    }
    if (!hasSubquery) strBuilder.append(")");
    return strBuilder.toString();
  }

  /*
   * If predicate is of the form "<SlotRef> [NOT] IN", returns the
   * SlotRef.
   */
  @Override
  public SlotRef getBoundSlot() {
    return getChild(0).unwrapSlotRef(true);
  }

  /**
   * Negates an InPredicate.
   */
  @Override
  public Expr negate() {
    return new InPredicate(getChild(0), children_.subList(1, children_.size()),
        !isNotIn_);
  }

  @Override
  public Expr clone() { return new InPredicate(this); }
}
