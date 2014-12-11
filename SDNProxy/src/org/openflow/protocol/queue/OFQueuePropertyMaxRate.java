package org.openflow.protocol.queue;

import java.nio.ByteBuffer;

import org.openflow.util.U16;

/**
 * Corresponds to the struct struct ofp_queue_prop_min_rate OpenFlow structure
 *
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class OFQueuePropertyMaxRate extends OFQueueProperty {
    public static int MINIMUM_LENGTH = 16;

    protected short rate;

    /**
     * 
     */
    public OFQueuePropertyMaxRate() {
        super();
        this.type = OFQueuePropertyType.MAX_RATE;
        this.length = U16.t(MINIMUM_LENGTH);
    }

    /**
     * @return the rate
     */
    public short getRate() {
        return rate;
    }

    /**
     * @param rate the rate to set
     */
    public OFQueuePropertyMaxRate setRate(short rate) {
        this.rate = rate;
        return this;
    }

    @Override
    public void readFrom(ByteBuffer data) {
        super.readFrom(data);
        this.rate = data.getShort();
        data.getInt(); // pad
        data.getShort(); // pad
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);
        data.putShort(this.rate);
        data.putInt(0); // pad
        data.putShort((short) 0); // pad
    }

    @Override
    public int hashCode() {
        final int prime = 3259;
        int result = super.hashCode();
        result = prime * result + rate;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (!(obj instanceof OFQueuePropertyMaxRate))
            return false;
        OFQueuePropertyMaxRate other = (OFQueuePropertyMaxRate) obj;
        if (rate != other.rate)
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "OFQueuePropertyMaxRate [type=" + type + ", rate=" + U16.f(rate) + "]";
    }
}
