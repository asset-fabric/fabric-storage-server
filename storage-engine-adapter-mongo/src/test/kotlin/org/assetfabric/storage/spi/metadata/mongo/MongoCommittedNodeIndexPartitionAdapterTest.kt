/*
 * Copyright (C) 2019 Asset Fabric contributors (https://github.com/orgs/asset-fabric/teams/asset-fabric-contributors)
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as
 *     published by the Free Software Foundation, either version 3 of the
 *     License, or (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.assetfabric.storage.spi.metadata.mongo

import org.assetfabric.storage.Path
import org.assetfabric.storage.RevisionNumber
import org.assetfabric.storage.State
import org.assetfabric.storage.spi.support.DefaultCommittedInverseNodeReferenceRepresentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.support.AnnotationConfigContextLoader
import reactor.core.publisher.Flux

@ExtendWith(SpringExtension::class)
@DirtiesContext
@ContextConfiguration(loader = AnnotationConfigContextLoader::class)
@TestPropertySource(properties = ["assetfabric.storage.metadata.adapter.type=mongo", "assetfabric.storage.metadata.adapter.mongo=embedded"])
@DisplayName("a committed node index partition adapter")
class MongoCommittedNodeIndexPartitionAdapterTest {

    @Configuration
    @Import(MongoCommittedNodeIndexPartitionAdapter::class, MongoTemplateConfiguration::class, EmbeddedMongoClientFactory::class)
    internal class Config

    @Autowired
    private lateinit var partitionAdapter: MongoCommittedNodeIndexPartitionAdapter

    @BeforeEach
    private fun reset() {
        partitionAdapter.reset().block()
    }

    @Test
    @DisplayName("should be able to store a committed node reference")
    fun testCreateNodeRepresentation() {
        val revision = RevisionNumber(5)
        val path = "/test"
        val refPath = "/test2"

        val repr = DefaultCommittedInverseNodeReferenceRepresentation(revision, Path(path), Path(refPath), State.NORMAL)
        val result = partitionAdapter.createInverseNodeReferences(Flux.just(repr))
        val reprs = result.collectList().block()!!
        assertEquals(1, reprs.size)
    }

    @Test
    @DisplayName("should be able to retrieve all committed node references at a given revision")
    fun testGetSessionWorkingNodes() {
        // source1 refers to target1 at revision 1
        val repr1 = DefaultCommittedInverseNodeReferenceRepresentation(RevisionNumber(1), Path("/target1"), Path("/source1"), State.NORMAL)
        // source2 refers to target1 at revision 2
        val repr2 = DefaultCommittedInverseNodeReferenceRepresentation(RevisionNumber(2), Path("/target1"), Path("/source2"), State.NORMAL)
        // source1's reference to target1 was deleted at revision 3
        val repr3 = DefaultCommittedInverseNodeReferenceRepresentation(RevisionNumber(3), Path("/target1"), Path("/source1"), State.DELETED)

        partitionAdapter.createInverseNodeReferences(Flux.just(repr1, repr2, repr3)).collectList().block()

        val rev1 = partitionAdapter.nodeReferences(Path("/target1"), RevisionNumber(1)).collectList().block()!!
        assertEquals(1, rev1.size)
        assertTrue(rev1.contains(repr1))

        val rev2 = partitionAdapter.nodeReferences(Path("/target1"), RevisionNumber(2)).collectList().block()!!
        assertEquals(2, rev2.size)
        assertTrue(rev2.contains(repr1))
        assertTrue(rev2.contains(repr2))

        val rev3 = partitionAdapter.nodeReferences(Path("/target1"), RevisionNumber(3)).collectList().block()!!
        assertEquals(2, rev3.size)
        assertTrue(rev3.contains(repr2))
        assertTrue(rev3.contains(repr3))

    }

}