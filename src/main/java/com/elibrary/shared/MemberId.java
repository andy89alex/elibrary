package com.elibrary.shared;

/**
 * Identity of a library member, taken from the authenticated principal.
 * There is no members table: member management is a separate domain, and none of
 * the lending invariants need member data beyond this identifier.
 */
public record MemberId(String value) {

    public MemberId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("member id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
