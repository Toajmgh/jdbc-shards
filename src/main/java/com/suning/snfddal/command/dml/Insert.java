/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.suning.snfddal.command.dml;

import java.util.ArrayList;
import java.util.HashMap;

import com.suning.snfddal.api.ErrorCode;
import com.suning.snfddal.command.Command;
import com.suning.snfddal.command.CommandInterface;
import com.suning.snfddal.command.Prepared;
import com.suning.snfddal.command.expression.Comparison;
import com.suning.snfddal.command.expression.ConditionAndOr;
import com.suning.snfddal.command.expression.Expression;
import com.suning.snfddal.command.expression.ExpressionColumn;
import com.suning.snfddal.command.expression.Parameter;
import com.suning.snfddal.dbobject.Right;
import com.suning.snfddal.dbobject.index.Index;
import com.suning.snfddal.dbobject.table.Column;
import com.suning.snfddal.dbobject.table.Table;
import com.suning.snfddal.engine.Session;
import com.suning.snfddal.message.DbException;
import com.suning.snfddal.result.ResultInterface;
import com.suning.snfddal.result.ResultTarget;
import com.suning.snfddal.result.Row;
import com.suning.snfddal.util.New;
import com.suning.snfddal.util.StatementBuilder;
import com.suning.snfddal.value.Value;
import com.suning.snfddal.value.ValueNull;

/**
 * This class represents the statement
 * INSERT
 */
public class Insert extends Prepared implements ResultTarget {

    private Table table;
    private Column[] columns;
    private final ArrayList<Expression[]> list = New.arrayList();
    private Query query;
    private boolean sortedInsertMode;
    private int rowNumber;
    private boolean insertFromSelect;

    /**
     * For MySQL-style INSERT ... ON DUPLICATE KEY UPDATE ....
     */
    private HashMap<Column, Expression> duplicateKeyAssignmentMap;

    public Insert(Session session) {
        super(session);
    }

    @Override
    public void setCommand(Command command) {
        super.setCommand(command);
        if (query != null) {
            query.setCommand(command);
        }
    }

    public void setTable(Table table) {
        this.table = table;
    }

    public void setColumns(Column[] columns) {
        this.columns = columns;
    }

    public void setQuery(Query query) {
        this.query = query;
    }

    /**
     * Keep a collection of the columns to pass to update if a duplicate key
     * happens, for MySQL-style INSERT ... ON DUPLICATE KEY UPDATE ....
     *
     * @param column the column
     * @param expression the expression
     */
    public void addAssignmentForDuplicate(Column column, Expression expression) {
        if (duplicateKeyAssignmentMap == null) {
            duplicateKeyAssignmentMap = New.hashMap();
        }
        if (duplicateKeyAssignmentMap.containsKey(column)) {
            throw DbException.get(ErrorCode.DUPLICATE_COLUMN_NAME_1,
                    column.getName());
        }
        duplicateKeyAssignmentMap.put(column, expression);
    }

    /**
     * Add a row to this merge statement.
     *
     * @param expr the list of values
     */
    public void addRow(Expression[] expr) {
        list.add(expr);
    }

    @Override
    public int update() {
        return insertRows();
    }

    private int insertRows() {
        session.getUser().checkRight(table, Right.INSERT);
        setCurrentRowNumber(0);
        //table.fire(session, Trigger.INSERT, true);
        rowNumber = 0;
        int listSize = list.size();
        if (listSize > 0) {
            int columnLen = columns.length;
            for (int x = 0; x < listSize; x++) {
                //session.startStatementWithinTransaction();
                Row newRow = table.getTemplateRow();
                Expression[] expr = list.get(x);
                setCurrentRowNumber(x + 1);
                for (int i = 0; i < columnLen; i++) {
                    Column c = columns[i];
                    int index = c.getColumnId();
                    Expression e = expr[i];
                    if (e != null) {
                        // e can be null (DEFAULT)
                        e = e.optimize(session);
                        try {
                            Value v = c.convert(e.getValue(session));
                            newRow.setValue(index, v);
                        } catch (DbException ex) {
                            throw setRow(ex, x, getSQL(expr));
                        }
                    }
                }
                rowNumber++;
                //table.validateConvertUpdateSequence(session, newRow);
                //boolean done = table.fireBeforeRow(session, null, newRow);
                //if (!done) {}
                //table.lock(session, true, false);
                try {
                    table.addRow(session, newRow);
                } catch (DbException de) {
                    handleOnDuplicate(de);
                }
                //table.fireAfterRow(session, null, newRow, false);
            
            }
        } else {
            //table.lock(session, true, false);
            if (insertFromSelect) {
                query.query(0, this);
            } else {
                ResultInterface rows = query.query(0);
                while (rows.next()) {
                    Value[] r = rows.currentRow();
                    addRow(r);
                }
                rows.close();
            }
        }
        //table.fire(session, Trigger.INSERT, false);
        return rowNumber;
    }

    @Override
    public void addRow(Value[] values) {
        Row newRow = table.getTemplateRow();
        setCurrentRowNumber(++rowNumber);
        for (int j = 0, len = columns.length; j < len; j++) {
            Column c = columns[j];
            int index = c.getColumnId();
            try {
                Value v = c.convert(values[j]);
                newRow.setValue(index, v);
            } catch (DbException ex) {
                throw setRow(ex, rowNumber, getSQL(values));
            }
        }
        //table.validateConvertUpdateSequence(session, newRow);
        //boolean done = table.fireBeforeRow(session, null, newRow);
        table.addRow(session, newRow);
        //if (!done) {
            //table.fireAfterRow(session, null, newRow, false);
        //}
    }

    @Override
    public int getRowCount() {
        return rowNumber;
    }

    @Override
    public String getPlanSQL() {
        StatementBuilder buff = new StatementBuilder("INSERT INTO ");
        buff.append(table.getSQL()).append('(');
        for (Column c : columns) {
            buff.appendExceptFirst(", ");
            buff.append(c.getSQL());
        }
        buff.append(")\n");
        if (insertFromSelect) {
            buff.append("DIRECT ");
        }
        if (sortedInsertMode) {
            buff.append("SORTED ");
        }
        if (list.size() > 0) {
            buff.append("VALUES ");
            int row = 0;
            if (list.size() > 1) {
                buff.append('\n');
            }
            for (Expression[] expr : list) {
                if (row++ > 0) {
                    buff.append(",\n");
                }
                buff.append('(');
                buff.resetCount();
                for (Expression e : expr) {
                    buff.appendExceptFirst(", ");
                    if (e == null) {
                        buff.append("DEFAULT");
                    } else {
                        buff.append(e.getSQL());
                    }
                }
                buff.append(')');
            }
        } else {
            buff.append(query.getPlanSQL());
        }
        return buff.toString();
    }

    @Override
    public void prepare() {
        if (columns == null) {
            if (list.size() > 0 && list.get(0).length == 0) {
                // special case where table is used as a sequence
                columns = new Column[0];
            } else {
                columns = table.getColumns();
            }
        }
        if (list.size() > 0) {
            for (Expression[] expr : list) {
                if (expr.length != columns.length) {
                    throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
                }
                for (int i = 0, len = expr.length; i < len; i++) {
                    Expression e = expr[i];
                    if (e != null) {
                        e = e.optimize(session);
                        if (e instanceof Parameter) {
                            Parameter p = (Parameter) e;
                            p.setColumn(columns[i]);
                        }
                        expr[i] = e;
                    }
                }
            }
        } else {
            query.prepare();
            if (query.getColumnCount() != columns.length) {
                throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
            }
        }
    }

    @Override
    public boolean isTransactional() {
        return true;
    }

    @Override
    public ResultInterface queryMeta() {
        return null;
    }

    public void setSortedInsertMode(boolean sortedInsertMode) {
        this.sortedInsertMode = sortedInsertMode;
    }

    @Override
    public int getType() {
        return CommandInterface.INSERT;
    }

    public void setInsertFromSelect(boolean value) {
        this.insertFromSelect = value;
    }

    @Override
    public boolean isCacheable() {
        return duplicateKeyAssignmentMap == null ||
                duplicateKeyAssignmentMap.isEmpty();
    }

    private void handleOnDuplicate(DbException de) {
        if (de.getErrorCode() != ErrorCode.DUPLICATE_KEY_1) {
            throw de;
        }
        if (duplicateKeyAssignmentMap == null ||
                duplicateKeyAssignmentMap.isEmpty()) {
            throw de;
        }

        ArrayList<String> variableNames = new ArrayList<String>(
                duplicateKeyAssignmentMap.size());
        for (int i = 0; i < columns.length; i++) {
            String key = session.getCurrentSchemaName() + "." +
                    table.getName() + "." + columns[i].getName();
            variableNames.add(key);
            session.setVariable(key,
                    list.get(getCurrentRowNumber() - 1)[i].getValue(session));
        }

        StatementBuilder buff = new StatementBuilder("UPDATE ");
        buff.append(table.getSQL()).append(" SET ");
        for (Column column : duplicateKeyAssignmentMap.keySet()) {
            buff.appendExceptFirst(", ");
            Expression ex = duplicateKeyAssignmentMap.get(column);
            buff.append(column.getSQL()).append("=").append(ex.getSQL());
        }
        buff.append(" WHERE ");
        Index foundIndex = searchForUpdateIndex();
        if (foundIndex == null) {
            throw DbException.getUnsupportedException(
                    "Unable to apply ON DUPLICATE KEY UPDATE, no index found!");
        }
        buff.append(prepareUpdateCondition(foundIndex).getSQL());
        String sql = buff.toString();
        Prepared command = session.prepare(sql);
        for (Parameter param : command.getParameters()) {
            Parameter insertParam = parameters.get(param.getIndex());
            param.setValue(insertParam.getValue(session));
        }
        command.update();
        for (String variableName : variableNames) {
            session.setVariable(variableName, ValueNull.INSTANCE);
        }
    }

    private Index searchForUpdateIndex() {
        Index foundIndex = null;
        for (Index index : table.getIndexes()) {
            if (index.getIndexType().isPrimaryKey() || index.getIndexType().isUnique()) {
                for (Column indexColumn : index.getColumns()) {
                    for (Column insertColumn : columns) {
                        if (indexColumn.getName() == insertColumn.getName()) {
                            foundIndex = index;
                            break;
                        }
                        foundIndex = null;
                    }
                    if (foundIndex == null) {
                        break;
                    }
                }
                if (foundIndex != null) {
                    break;
                }
            }
        }
        return foundIndex;
    }

    private Expression prepareUpdateCondition(Index foundIndex) {
        Expression condition = null;
        for (Column column : foundIndex.getColumns()) {
            ExpressionColumn expr = new ExpressionColumn(session.getDatabase(),
                    session.getCurrentSchemaName(), table.getName(), column.getName());
            for (int i = 0; i < columns.length; i++) {
                if (expr.getColumnName().equals(columns[i].getName())) {
                    if (condition == null) {
                        condition = new Comparison(session, Comparison.EQUAL,
                                expr, list.get(getCurrentRowNumber() - 1)[i++]);
                    } else {
                        condition = new ConditionAndOr(ConditionAndOr.AND, condition,
                                new Comparison(session, Comparison.EQUAL,
                                expr, list.get(0)[i++]));
                    }
                }
            }
        }
        return condition;
    }

}
