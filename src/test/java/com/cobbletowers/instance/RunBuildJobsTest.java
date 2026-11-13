package com.cobbletowers.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bookkeeping of floors a party waits on, and the lease swap a floor change into another building relies on. */
class RunBuildJobsTest {

    private static final UUID RUN = UUID.fromString("eeeeeeee-1111-0000-0000-000000000001");
    private static final UUID SOMEONE = UUID.fromString("eeeeeeee-2222-0000-0000-000000000002");

    @BeforeEach
    @AfterEach
    void clear() {
        RunBuildJobs.clear();
        InstanceAllocator.resetForTests();
    }

    @Test
    @DisplayName("a cell taken ready from the warm pool is ready at once, and a party waiting on it does not hold the pool back")
    void adoptedCellIsReady() {
        UUID tenant = RunBuildJobs.nextFloorTenant(RUN);
        RunBuildJobs.adoptReady(tenant, tenant, 12);
        assertEquals(Optional.of(new RunBuildJobs.Status(RunBuildJobs.State.READY, 12, tenant, List.of())), RunBuildJobs.poll(tenant));
        assertFalse(RunBuildJobs.busy(), "nothing is building");
        RunBuildJobs.forget(tenant);
        assertEquals(Optional.empty(), RunBuildJobs.poll(tenant));
    }

    @Test
    @DisplayName("the run, its next-floor cell and the cell it leaves are three different lease holders")
    void tenantsAreDistinct() {
        UUID next = RunBuildJobs.nextFloorTenant(RUN);
        UUID retiring = RunBuildJobs.retiringTenant(RUN);
        assertNotEquals(RUN, next);
        assertNotEquals(RUN, retiring);
        assertNotEquals(next, retiring);
        assertEquals(next, RunBuildJobs.nextFloorTenant(RUN), "the same run always asks after the same job");
    }

    @Test
    @DisplayName("a run that ends gives back the cell built for it, but only one its job still holds")
    void abandonRetiresOnlyAHeldCell() {
        UUID tenant = RunBuildJobs.nextFloorTenant(RUN);
        InstanceAllocator.transferLease(12, SOMEONE, tenant);   // leased to the job's tenant
        RunBuildJobs.adoptReady(tenant, tenant, 12);
        RunBuildJobs.abandon(tenant);
        assertEquals(Optional.empty(), RunBuildJobs.poll(tenant));
        assertEquals(1, RunBuildJobs.retiringCount(), "the built cell is retired, not leaked");

        RunBuildJobs.clear();
        RunBuildJobs.adoptReady(RUN, RUN, 20);   // the run's first floor, already taken by the run's record: its lease moved on
        InstanceAllocator.transferLease(20, SOMEONE, UUID.randomUUID());
        RunBuildJobs.abandon(RUN);
        assertEquals(0, RunBuildJobs.retiringCount(), "a cell somebody else holds is not ours to release");
        RunBuildJobs.abandon(UUID.randomUUID());   // no job: nothing happens
    }

    @Test
    @DisplayName("the floor change's lease swap: the run holds the new cell, the old one is held for retiring")
    void leaseSwap() {
        UUID tenant = RunBuildJobs.nextFloorTenant(RUN);
        UUID retiring = RunBuildJobs.retiringTenant(RUN);
        InstanceAllocator.transferLease(3, SOMEONE, RUN);       // the cell the run plays in
        InstanceAllocator.transferLease(9, SOMEONE, tenant);    // the next floor, built under its own tenant

        InstanceAllocator.transferLease(3, RUN, retiring);
        InstanceAllocator.transferLease(9, tenant, RUN);

        assertEquals(OptionalInt.of(9), InstanceAllocator.cellOf(RUN));
        assertEquals(Optional.of(RUN), InstanceAllocator.runIn(9));
        assertEquals(Optional.of(retiring), InstanceAllocator.runIn(3), "the old cell is not free until it is cleared");
        assertTrue(InstanceAllocator.cellOf(tenant).isEmpty(), "the next-floor tenant holds nothing once the run has moved");
    }
}
