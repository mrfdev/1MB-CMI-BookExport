package com.mrfloris.exportbook;

import java.io.IOException;

/** Test seam for simulating termination at one exact publication boundary. */
@FunctionalInterface
interface PublicationFaultInjector {
    void check(PublicationBoundary boundary) throws IOException;

    static PublicationFaultInjector none() {
        return ignored -> {
        };
    }
}
