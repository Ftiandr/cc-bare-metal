package dev.ccbaremetal.kernel.periph;

/** A peripheral call failed; the message is shown to the guest like a Lua error. */
public final class PeripheralException extends Exception {
    public PeripheralException(String message) {
        super(message, null, false, false);
    }
}
