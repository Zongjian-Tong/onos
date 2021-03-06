/*
 * Copyright 2015 Open Networking Laboratory
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
package org.onosproject.net.newresource;

import com.google.common.testing.EqualsTester;
import org.junit.Test;
import org.onlab.packet.VlanId;
import org.onlab.util.Bandwidth;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;

import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class ResourceTest {

    private static final DeviceId D1 = DeviceId.deviceId("of:001");
    private static final DeviceId D2 = DeviceId.deviceId("of:002");
    private static final PortNumber P1 = PortNumber.portNumber(1);
    private static final VlanId VLAN1 = VlanId.vlanId((short) 100);
    private static final Bandwidth BW1 = Bandwidth.gbps(2);
    private static final Bandwidth BW2 = Bandwidth.gbps(1);

    @Test
    public void testEquals() {
        Resource resource1 = Resource.discrete(D1, P1, VLAN1);
        Resource sameAsResource1 = Resource.discrete(D1, P1, VLAN1);
        Resource resource2 = Resource.discrete(D2, P1, VLAN1);
        Resource resource3 = Resource.continuous(BW1.bps(), D1, P1, BW1);
        Resource sameAsResource3 = Resource.continuous(BW1.bps(), D1, P1, BW1);

        new EqualsTester()
                .addEqualityGroup(resource1, sameAsResource1)
                .addEqualityGroup(resource2)
                .addEqualityGroup(resource3, sameAsResource3)
                .testEquals();
    }

    @Test
    public void testComponents() {
        Resource port = Resource.discrete(D1, P1);

        assertThat(port.components(), contains(D1, P1));
    }

    @Test
    public void testIdEquality() {
        ResourceId id1 = Resource.discrete(D1, P1, VLAN1).id();
        ResourceId sameAsId1 = Resource.discrete(D1, P1, VLAN1).id();
        ResourceId id2 = Resource.discrete(D2, P1, VLAN1).id();
        ResourceId id3 = Resource.continuous(BW1.bps(), D1, P1, BW1).id();
        // intentionally set a different value
        ResourceId sameAsId3 = Resource.continuous(BW2.bps(), D1, P1, BW1).id();

        new EqualsTester()
                .addEqualityGroup(id1, sameAsId1)
                .addEqualityGroup(id2)
                .addEqualityGroup(id3, sameAsId3);
    }

    @Test
    public void testChild() {
        Resource r1 = Resource.discrete(D1).child(P1);
        Resource sameAsR2 = Resource.discrete(D1, P1);

        assertThat(r1, is(sameAsR2));
    }

    @Test
    public void testThereIsParent() {
        Resource resource = Resource.discrete(D1, P1, VLAN1);
        Resource parent = Resource.discrete(D1, P1);

        assertThat(resource.parent(), is(Optional.of(parent)));
    }

    @Test
    public void testNoParent() {
        Resource resource = Resource.discrete(D1);

        assertThat(resource.parent(), is(Optional.of(Resource.ROOT)));
    }

    @Test
    public void testBase() {
        Resource resource = Resource.discrete(D1);

        DeviceId child = (DeviceId) resource.last();
        assertThat(child, is(D1));
    }
}
