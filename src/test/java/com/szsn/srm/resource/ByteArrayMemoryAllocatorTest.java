package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.Units;
import org.junit.jupiter.api.Test;

/**
 * 真实分配器只在很小的块上自测：触碰页、按引用释放。不调用 {@code System.gc()}。
 */
class ByteArrayMemoryAllocatorTest {
    @Test
    void touchesPagesAndDropsReferencesOnClear() {
        ByteArrayMemoryAllocator allocator = new ByteArrayMemoryAllocator();
        assertEquals(Units.BLOCK, allocator.grow(Units.BLOCK));
        assertEquals(1, allocator.retainedChunks());
        assertEquals(1, allocator.payloadSample());
        assertEquals(0L, allocator.release(Units.BLOCK));
        assertEquals(0, allocator.retainedChunks());

        assertEquals(Units.BLOCK, allocator.grow(Units.BLOCK));
        allocator.clear();
        assertEquals(0L, allocator.heldBytes());
        assertEquals(0, allocator.retainedChunks());
        assertEquals(0, allocator.payloadSample());
        assertEquals(Units.BLOCK, allocator.grow(Units.BLOCK));
        allocator.clear();
    }

    @Test
    void productionConfigUsesRealAllocatorAndBurner() {
        ResourceEngineConfig config = new ResourceEngineConfig();
        assertInstanceOf(ChecksumCpuBurner.class, config.cpuBurner());
        assertInstanceOf(ByteArrayMemoryAllocator.class, config.memoryAllocator());
    }

    @Test
    void rejectsNegativeGrowth() {
        ByteArrayMemoryAllocator allocator = new ByteArrayMemoryAllocator();
        assertThrows(IllegalArgumentException.class, () -> allocator.grow(-1));
        assertEquals(0L, allocator.heldBytes());
        assertTrue(allocator.retainedChunks() == 0);
    }
}
