/*
 * Copyright 2015-2016 Open Networking Laboratory
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
package org.onosproject.cluster;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.Objects;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

/**
 * Default {@link Partition} implementation.
 */
public class DefaultPartition implements Partition {

    private final PartitionId id;
    private final Collection<NodeId> members;

    private DefaultPartition() {
        id = null;
        members = null;
    }

    public DefaultPartition(PartitionId id, Collection<NodeId> members) {
        this.id = checkNotNull(id);
        this.members = ImmutableSet.copyOf(members);
    }

    @Override
    public PartitionId getId() {
        return this.id;
    }

    @Override
    public Collection<NodeId> getMembers() {
        return this.members;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(getClass())
                .add("id", id)
                .add("members", members)
                .toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, members);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DefaultPartition)) {
            return false;
        }
        DefaultPartition that = (DefaultPartition) other;
        return this.getId().equals(that.getId()) &&
                Sets.symmetricDifference(Sets.newHashSet(this.members), Sets.newHashSet(that.members)).isEmpty();
    }
}