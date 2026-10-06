package android.text

/**
 * Audit-only platform primitive shim: the mockable Android jar returns false even
 * for isEmpty(null), causing BillingFlowParams.Builder to reject valid controls.
 * This restores real primitive behavior, without mocking any app/Billing policy.
 * Included only by the round8 audit init script, never production or normal tests.
 */
object TextUtils {
 @JvmStatic fun isEmpty(value:CharSequence?):Boolean=value==null || value.isEmpty()
 @JvmStatic fun equals(a:CharSequence?,b:CharSequence?):Boolean = a===b || (a!=null && b!=null && a.toString()==b.toString())
}
