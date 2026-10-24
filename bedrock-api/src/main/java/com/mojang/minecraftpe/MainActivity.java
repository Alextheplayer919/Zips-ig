package com.mojang.minecraftpe;

/** Compile-only superclass signature. NEVER packaged or instantiated at runtime. */
public class MainActivity extends android.app.Activity {
    public MainActivity() { throw new AssertionError("Compile-only API must never be packaged"); }
    @Override public void onCreate(android.os.Bundle state) { throw new AssertionError("Compile-only API"); }
}
