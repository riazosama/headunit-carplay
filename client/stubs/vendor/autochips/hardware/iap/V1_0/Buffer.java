package vendor.autochips.hardware.iap.V1_0;

import java.util.ArrayList;

/**
 * COMPILE-TIME ONLY STUB (see IIAPService.java in this directory).
 *
 * Real HIDL struct: mData is a final ArrayList<Byte>, mLen is a mutable int.
 * The HAL-side IAP::read() lambda copies mData and takes mLen as the number of
 * bytes that were read (verified in
 * <internal notes>, IAP::read operator()).
 */
public final class Buffer {

    public final ArrayList<Byte> mData = new ArrayList<Byte>();

    public int mLen;
}
