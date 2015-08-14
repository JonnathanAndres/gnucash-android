/*
 * Copyright (c) 2014 Ngewi Fet <ngewif@gmail.com>
 * Copyright (c) 2014 Yongxin Wang <fefe.wyx@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gnucash.android.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.database.sqlite.SQLiteStatement;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import org.gnucash.android.app.GnuCashApplication;
import org.gnucash.android.model.AccountType;
import org.gnucash.android.model.Money;
import org.gnucash.android.model.Split;
import org.gnucash.android.model.TransactionType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static org.gnucash.android.db.DatabaseSchema.SplitEntry;
import static org.gnucash.android.db.DatabaseSchema.TransactionEntry;

/**
 * Database adapter for managing transaction splits in the database
 *
 * @author Ngewi Fet <ngewif@gmail.com>
 * @author Yongxin Wang <fefe.wyx@gmail.com>
 * @author Oleksandr Tyshkovets <olexandr.tyshkovets@gmail.com>
 */
public class SplitsDbAdapter extends DatabaseAdapter<Split> {

    public SplitsDbAdapter(SQLiteDatabase db) {
        super(db, SplitEntry.TABLE_NAME);
        LOG_TAG = "SplitsDbAdapter";
    }

    /**
     * Returns application-wide instance of the database adapter
     * @return SplitsDbAdapter instance
     */
    public static SplitsDbAdapter getInstance(){
        return GnuCashApplication.getSplitsDbAdapter();
    }

    /**
     * Adds a split to the database.
     * The transactions belonging to the split are marked as exported
     * @param split {@link org.gnucash.android.model.Split} to be recorded in DB
     */
    public void addRecord(@NonNull final Split split){
        Log.d(LOG_TAG, "Replace transaction split in db");
        super.addRecord(split);

        long transactionId = getTransactionID(split.getTransactionUID());
        //when a split is updated, we want mark the transaction as not exported
        updateRecord(TransactionEntry.TABLE_NAME, transactionId,
                TransactionEntry.COLUMN_EXPORTED, String.valueOf(0));

        //modifying a split means modifying the accompanying transaction as well
        updateRecord(TransactionEntry.TABLE_NAME, transactionId,
                TransactionEntry.COLUMN_MODIFIED_AT, Long.toString(System.currentTimeMillis()));
    }

    @Override
    protected SQLiteStatement compileReplaceStatement(Split split) {
        if (mReplaceStatement == null) {
            mReplaceStatement = mDb.compileStatement("REPLACE INTO " + SplitEntry.TABLE_NAME + " ( "
                    + SplitEntry.COLUMN_UID + " , "
                    + SplitEntry.COLUMN_MEMO + " , "
                    + SplitEntry.COLUMN_TYPE + " , "
                    + SplitEntry.COLUMN_VALUE_NUM + " , "
                    + SplitEntry.COLUMN_VALUE_DENOM + " , "
                    + SplitEntry.COLUMN_QUANTITY_NUM + " , "
                    + SplitEntry.COLUMN_QUANTITY_DENOM + " , "
                    + SplitEntry.COLUMN_CREATED_AT + " , "
                    + SplitEntry.COLUMN_ACCOUNT_UID + " , "
                    + SplitEntry.COLUMN_TRANSACTION_UID + " ) VALUES ( ? , ? , ? , ? , ? , ? , ? , ? , ? , ? ) ");
        }

        mReplaceStatement.clearBindings();
        mReplaceStatement.bindString(1, split.getUID());
        if (split.getMemo() != null) {
            mReplaceStatement.bindString(2, split.getMemo());
        }
        mReplaceStatement.bindString(3, split.getType().name());
        mReplaceStatement.bindLong(4, split.getValue().multiply(split.getValue().getNumberOfDecimalPlaces()).intValue());
        mReplaceStatement.bindLong(5, (long)Math.pow(10, split.getValue().getNumberOfDecimalPlaces()));
        mReplaceStatement.bindLong(6, split.getQuantity().multiply(split.getQuantity().getNumberOfDecimalPlaces()).intValue());
        mReplaceStatement.bindLong(7, (long)Math.pow(10, split.getQuantity().getNumberOfDecimalPlaces()));
        mReplaceStatement.bindString(8, split.getCreatedTimestamp().toString());
        mReplaceStatement.bindString(9, split.getAccountUID());
        mReplaceStatement.bindString(10, split.getTransactionUID());

        return mReplaceStatement;
    }

    /**
     * Builds a split instance from the data pointed to by the cursor provided
     * <p>This method will not move the cursor in any way. So the cursor should already by pointing to the correct entry</p>
     * @param cursor Cursor pointing to transaction record in database
     * @return {@link org.gnucash.android.model.Split} instance
     */
    public Split buildModelInstance(@NonNull final Cursor cursor){
        long valueNum       = cursor.getLong(cursor.getColumnIndexOrThrow(SplitEntry.COLUMN_VALUE_NUM));
        long valueDenom     = cursor.getLong(cursor.getColumnIndexOrThrow(SplitEntry.COLUMN_VALUE_DENOM));
        long quantityNum    = cursor.getLong(cursor.getColumnIndexOrThrow(SplitEntry.COLUMN_QUANTITY_NUM));
        long quantityDenom  = cursor.getLong(cursor.getColumnIndexOrThrow(SplitEntry.COLUMN_QUANTITY_DENOM));
        String typeName     = cursor.getString(cursor.getColumnIndexOrThrow(SplitEntry.COLUMN_TYPE));
        String accountUID   = cursor.getString(cursor.getColumnIndexOrThrow(SplitEntry.COLUMN_ACCOUNT_UID));
        String transxUID    = cursor.getString(cursor.getColumnIndexOrThrow(SplitEntry.COLUMN_TRANSACTION_UID));
        String memo         = cursor.getString(cursor.getColumnIndexOrThrow(SplitEntry.COLUMN_MEMO));

        String currencyCode = getAccountCurrencyCode(accountUID);
        Money value = new Money(valueNum, valueDenom, currencyCode);
        Money quantity = new Money(quantityNum, quantityDenom, currencyCode);

        Split split = new Split(value, accountUID);
        split.setQuantity(quantity);
        populateBaseModelAttributes(cursor, split);
        split.setTransactionUID(transxUID);
        split.setType(TransactionType.valueOf(typeName));
        split.setMemo(memo);

        return split;
    }

    /**
     * Returns the sum of the splits for given set of accounts.
     * This takes into account the kind of movement caused by the split in the account (which also depends on account type)
     * The Caller must make sure all accounts have the currency, which is passed in as currencyCode
     * @param accountUIDList List of String unique IDs of given set of accounts
     * @param currencyCode currencyCode for all the accounts in the list
     * @param hasDebitNormalBalance Does the final balance has normal debit credit meaning
     * @return Balance of the splits for this account
     */
    public Money computeSplitBalance(List<String> accountUIDList, String currencyCode, boolean hasDebitNormalBalance){
        return calculateSplitBalance(accountUIDList, currencyCode, hasDebitNormalBalance, -1, -1);
    }

    /**
     * Returns the sum of the splits for given set of accounts within the specified time range.
     * This takes into account the kind of movement caused by the split in the account (which also depends on account type)
     * The Caller must make sure all accounts have the currency, which is passed in as currencyCode
     * @param accountUIDList List of String unique IDs of given set of accounts
     * @param currencyCode currencyCode for all the accounts in the list
     * @param hasDebitNormalBalance Does the final balance has normal debit credit meaning
     * @param startTimestamp the start timestamp of the time range
     * @param endTimestamp the end timestamp of the time range
     * @return Balance of the splits for this account within the specified time range
     */
    public Money computeSplitBalance(List<String> accountUIDList, String currencyCode, boolean hasDebitNormalBalance,
                                     long startTimestamp, long endTimestamp){
        return calculateSplitBalance(accountUIDList, currencyCode, hasDebitNormalBalance, startTimestamp, endTimestamp);
    }

    private Money calculateSplitBalance(List<String> accountUIDList, String currencyCode, boolean hasDebitNormalBalance,
                          long startTimestamp, long endTimestamp){
        if (accountUIDList.size() == 0){
            return new Money("0", currencyCode);
        }

        Cursor cursor;
        String[] selectionArgs = null;
        String selection = SplitEntry.TABLE_NAME + "." + SplitEntry.COLUMN_ACCOUNT_UID + " in ( '" + TextUtils.join("' , '", accountUIDList) + "' ) AND " +
                SplitEntry.TABLE_NAME + "." + SplitEntry.COLUMN_TRANSACTION_UID + " = " + TransactionEntry.TABLE_NAME + "." + TransactionEntry.COLUMN_UID + " AND " +
                TransactionEntry.TABLE_NAME + "." + TransactionEntry.COLUMN_TEMPLATE + " = 0";

        if (startTimestamp != -1 && endTimestamp != -1) {
            selection += " AND " + TransactionEntry.TABLE_NAME + "." + TransactionEntry.COLUMN_TIMESTAMP + " BETWEEN ? AND ? ";
            selectionArgs = new String[]{String.valueOf(startTimestamp), String.valueOf(endTimestamp)};
        } else if (startTimestamp == -1 && endTimestamp != -1) {
            selection += " AND " + TransactionEntry.TABLE_NAME + "." + TransactionEntry.COLUMN_TIMESTAMP + " <= ?";
            selectionArgs = new String[]{String.valueOf(endTimestamp)};
        } else if (startTimestamp != -1 && endTimestamp == -1) {
            selection += " AND " + TransactionEntry.TABLE_NAME + "." + TransactionEntry.COLUMN_TIMESTAMP + " >= ?";
            selectionArgs = new String[]{String.valueOf(startTimestamp)};
        }

        String splitValueSql = SplitEntry.TABLE_NAME + "." + SplitEntry.COLUMN_VALUE_NUM + " / " + SplitEntry.TABLE_NAME + "." + SplitEntry.COLUMN_VALUE_DENOM;
        cursor = mDb.query(SplitEntry.TABLE_NAME + " , " + TransactionEntry.TABLE_NAME,
                new String[]{"TOTAL ( CASE WHEN " + SplitEntry.TABLE_NAME + "." + SplitEntry.COLUMN_TYPE + " = 'DEBIT' THEN " +
                        splitValueSql + " ELSE - " + splitValueSql + " END )"},
                selection, selectionArgs, null, null, null);

        try {
            if (cursor.moveToFirst()) {
                double amount = cursor.getDouble(0);
                cursor.close();
                Log.d(LOG_TAG, "amount return " + amount);
                if (!hasDebitNormalBalance) {
                    amount = -amount;
                }
                return new Money(BigDecimal.valueOf(amount).setScale(2, BigDecimal.ROUND_HALF_UP), Currency.getInstance(currencyCode));
            }
        } finally {
            cursor.close();
        }
        return new Money("0", currencyCode);
    }

    /**
     * Returns the list of splits for a transaction
     * @param transactionUID String unique ID of transaction
     * @return List of {@link org.gnucash.android.model.Split}s
     */
    public List<Split> getSplitsForTransaction(String transactionUID){
        Cursor cursor = fetchSplitsForTransaction(transactionUID);
        List<Split> splitList = new ArrayList<Split>();
        try {
            while (cursor.moveToNext()) {
                splitList.add(buildModelInstance(cursor));
            }
        } finally {
            cursor.close();
        }
        return splitList;
    }

    /**
     * Returns the list of splits for a transaction
     * @param transactionID DB record ID of the transaction
     * @return List of {@link org.gnucash.android.model.Split}s
     * @see #getSplitsForTransaction(String)
     * @see #getTransactionUID(long)
     */
    public List<Split> getSplitsForTransaction(long transactionID){
        return getSplitsForTransaction(getTransactionUID(transactionID));
    }

    /**
     * Fetch splits for a given transaction within a specific account
     * @param transactionUID String unique ID of transaction
     * @param accountUID String unique ID of account
     * @return List of splits
     */
    public List<Split> getSplitsForTransactionInAccount(String transactionUID, String accountUID){
        Cursor cursor = fetchSplitsForTransactionAndAccount(transactionUID, accountUID);
        List<Split> splitList = new ArrayList<Split>();
        if (cursor != null){
            while (cursor.moveToNext()){
                splitList.add(buildModelInstance(cursor));
            }
            cursor.close();
        }
        return splitList;
    }

    /**
     * Fetches a collection of splits for a given condition and sorted by <code>sortOrder</code>
     * @param where String condition, formatted as SQL WHERE clause
     * @param whereArgs where args
     * @param sortOrder Sort order for the returned records
     * @return Cursor to split records
     */
    public Cursor fetchSplits(String where, String[] whereArgs, String sortOrder){
        return mDb.query(SplitEntry.TABLE_NAME,
                null, where, whereArgs, null, null, sortOrder);
    }

    /**
     * Returns a Cursor to a dataset of splits belonging to a specific transaction
     * @param transactionUID Unique idendtifier of the transaction
     * @return Cursor to splits
     */
    public Cursor fetchSplitsForTransaction(String transactionUID){
        Log.v(LOG_TAG, "Fetching all splits for transaction UID " + transactionUID);
        return mDb.query(SplitEntry.TABLE_NAME,
                null, SplitEntry.COLUMN_TRANSACTION_UID + " = ?",
                new String[]{transactionUID},
                null, null, null);
    }

    /**
     * Fetches splits for a given account
     * @param accountUID String unique ID of account
     * @return Cursor containing splits dataset
     */
    public Cursor fetchSplitsForAccount(String accountUID){
        Log.d(LOG_TAG, "Fetching all splits for account UID " + accountUID);

        //This is more complicated than a simple "where account_uid=?" query because
        // we need to *not* return any splits which belong to recurring transactions
        SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
        queryBuilder.setTables(TransactionEntry.TABLE_NAME
                + " INNER JOIN " +  SplitEntry.TABLE_NAME + " ON "
                + TransactionEntry.TABLE_NAME + "." + TransactionEntry.COLUMN_UID + " = "
                + SplitEntry.TABLE_NAME + "." + SplitEntry.COLUMN_TRANSACTION_UID);
        queryBuilder.setDistinct(true);
        String[] projectionIn = new String[]{SplitEntry.TABLE_NAME + ".*"};
        String selection = SplitEntry.TABLE_NAME + "." + SplitEntry.COLUMN_ACCOUNT_UID + " = ?"
                + " AND " + TransactionEntry.TABLE_NAME + "." + TransactionEntry.COLUMN_TEMPLATE + " = 0";
        String[] selectionArgs = new String[]{accountUID};
        String sortOrder = TransactionEntry.TABLE_NAME + "." + TransactionEntry.COLUMN_TIMESTAMP + " DESC";

        return queryBuilder.query(mDb, projectionIn, selection, selectionArgs, null, null, sortOrder);

    }

    /**
     * Returns a cursor to splits for a given transaction and account
     * @param transactionUID Unique idendtifier of the transaction
     * @param accountUID String unique ID of account
     * @return Cursor to splits data set
     */
    public Cursor fetchSplitsForTransactionAndAccount(String transactionUID, String accountUID){
        if (transactionUID == null || accountUID == null)
            return null;

        Log.v(LOG_TAG, "Fetching all splits for transaction ID " + transactionUID
                + "and account ID " + accountUID);
        return mDb.query(SplitEntry.TABLE_NAME,
                null, SplitEntry.COLUMN_TRANSACTION_UID + " = ? AND "
                        + SplitEntry.COLUMN_ACCOUNT_UID + " = ?",
                new String[]{transactionUID, accountUID},
                null, null, SplitEntry.COLUMN_VALUE_NUM + " ASC");
    }

    /**
     * Returns the unique ID of a transaction given the database record ID of same
     * @param transactionId Database record ID of the transaction
     * @return String unique ID of the transaction or null if transaction with the ID cannot be found.
     */
    public String getTransactionUID(long transactionId){
        Cursor cursor = mDb.query(TransactionEntry.TABLE_NAME,
                new String[]{TransactionEntry.COLUMN_UID},
                TransactionEntry._ID + " = " + transactionId,
                null, null, null, null);

        try {
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(TransactionEntry.COLUMN_UID));
            } else {
                throw new IllegalArgumentException("transaction " + transactionId + " does not exist");
            }
        } finally {
            cursor.close();
        }
    }

    @Override
    public boolean deleteRecord(long rowId) {
        Split split = getRecord(rowId);
        String transactionUID = split.getTransactionUID();
        boolean result = mDb.delete(SplitEntry.TABLE_NAME, SplitEntry._ID + "=" + rowId, null) > 0;

        if (!result) //we didn't delete for whatever reason, invalid rowId etc
            return false;

        //if we just deleted the last split, then remove the transaction from db
        Cursor cursor = fetchSplitsForTransaction(transactionUID);
        try {
            if (cursor.getCount() > 0) {
                long transactionID = getTransactionID(transactionUID);
                result = mDb.delete(TransactionEntry.TABLE_NAME,
                        TransactionEntry._ID + "=" + transactionID, null) > 0;
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    /**
     * Returns the database record ID for the specified transaction UID
     * @param transactionUID Unique idendtifier of the transaction
     * @return Database record ID for the transaction
     */
    public long getTransactionID(String transactionUID) {
        Cursor c = mDb.query(TransactionEntry.TABLE_NAME,
                new String[]{TransactionEntry._ID},
                TransactionEntry.COLUMN_UID + "=?",
                new String[]{transactionUID}, null, null, null);
        try {
            if (c.moveToFirst()) {
                return c.getLong(0);
            } else {
                throw new IllegalArgumentException("transaction " + transactionUID + " does not exist");
            }
        } finally {
            c.close();
        }
    }

}
