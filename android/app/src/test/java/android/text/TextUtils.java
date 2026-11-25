package android.text;

/**
 * JVM unit tests only. android.jar's TextUtils is a stub that throws "Stub!", and the Play Billing
 * Library's own model classes (Purchase, ProductDetails, BillingFlowParams) call these two methods.
 * Test classes come before android.jar on the unit-test classpath, so this copy is the one used.
 */
public final class TextUtils {
    private TextUtils() {}

    public static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.toString().contentEquals(b);
    }
}
