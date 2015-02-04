/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.suning.snfddal.value;

import java.text.CollationKey;
import java.text.Collator;

import com.suning.snfddal.engine.SysProperties;
import com.suning.snfddal.message.DbException;
import com.suning.snfddal.util.SmallLRUCache;

/**
 * The default implementation of CompareMode. It uses java.text.Collator.
 */
public class CompareModeDefault extends CompareMode {

    private final Collator collator;
    private final SmallLRUCache<String, CollationKey> collationKeys;

    protected CompareModeDefault(String name, int strength,
            boolean binaryUnsigned) {
        super(name, strength, binaryUnsigned);
        collator = CompareMode.getCollator(name);
        if (collator == null) {
            throw DbException.throwInternalError(name);
        }
        collator.setStrength(strength);
        int cacheSize = SysProperties.COLLATOR_CACHE_SIZE;
        if (cacheSize != 0) {
            collationKeys = SmallLRUCache.newInstance(cacheSize);
        } else {
            collationKeys = null;
        }
    }

    @Override
    public int compareString(String a, String b, boolean ignoreCase) {
        if (ignoreCase) {
            // this is locale sensitive
            a = a.toUpperCase();
            b = b.toUpperCase();
        }
        int comp;
        if (collationKeys != null) {
            CollationKey aKey = getKey(a);
            CollationKey bKey = getKey(b);
            comp = aKey.compareTo(bKey);
        } else {
            comp = collator.compare(a, b);
        }
        return comp;
    }

    @Override
    public boolean equalsChars(String a, int ai, String b, int bi,
            boolean ignoreCase) {
        return compareString(a.substring(ai, ai + 1), b.substring(bi, bi + 1),
                ignoreCase) == 0;
    }

    private CollationKey getKey(String a) {
        synchronized (collationKeys) {
            CollationKey key = collationKeys.get(a);
            if (key == null) {
                key = collator.getCollationKey(a);
                collationKeys.put(a, key);
            }
            return key;
        }
    }

}
