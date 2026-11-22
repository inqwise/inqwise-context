package com.inqwise.context;

import com.inqwise.context.spi.ContextChainDataItem;

/** Checks intrinsic type parameters for the library's collection snapshots. */
final class ContextContainerTypes {
    private ContextContainerTypes() {
    }

    static void validateReplacement(ContextChainDataItem previous, ContextChainDataItem replacement) {
        if (previous instanceof ContextMapItem<?, ?> before) {
            if (!(replacement instanceof ContextMapItem<?, ?> after)
                    || before.keyType() != after.keyType() || before.valueType() != after.valueType()) {
                throw new ClassCastException("Replacement conflicts with stored context map types");
            }
        } else if (previous instanceof ContextSetItem<?> before) {
            if (!(replacement instanceof ContextSetItem<?> after) || before.elementType() != after.elementType()) {
                throw new ClassCastException("Replacement conflicts with stored context set type");
            }
        } else if (replacement instanceof ContextMapItem<?, ?> || replacement instanceof ContextSetItem<?>) {
            throw new ClassCastException("Context container cannot replace an unrelated stored item");
        }
    }
}
