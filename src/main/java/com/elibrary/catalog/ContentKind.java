package com.elibrary.catalog;

/**
 * Journals are modelled as a kind of catalogue item rather than a separate type.
 * A sealed Book/Journal hierarchy would add JPA inheritance mapping and a polymorphic
 * response shape without protecting any invariant that differs between the two.
 */
public enum ContentKind {
    BOOK, JOURNAL
}
