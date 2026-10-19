package com.etphonehome.pit;

import org.pitest.mutationtest.build.InterceptorParameters;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.build.MutationInterceptorFactory;
import org.pitest.plugin.Feature;

/** Registers {@link KotlinGeneratedCodeFilter} with PIT (on by default). */
public final class KotlinGeneratedCodeFilterFactory implements MutationInterceptorFactory {
    @Override
    public String description() {
        return "Kotlin compiler-generated code mutants filter (etphonehome)";
    }

    @Override
    public Feature provides() {
        return Feature.named("FETPHKOTLIN")
            .withOnByDefault(true)
            .withDescription("Filters mutants in code kotlinc generates: Intrinsics null checks, "
                + "coroutine ResultKt.throwOnFailure resume checks, null for a Unit coroutine result");
    }

    @Override
    public MutationInterceptor createInterceptor(InterceptorParameters params) {
        return new KotlinGeneratedCodeFilter();
    }
}
