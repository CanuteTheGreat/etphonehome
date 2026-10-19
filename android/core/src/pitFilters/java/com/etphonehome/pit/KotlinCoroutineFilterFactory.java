package com.etphonehome.pit;

import org.pitest.mutationtest.build.InterceptorParameters;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.build.MutationInterceptorFactory;
import org.pitest.plugin.Feature;

/** Registers {@link KotlinCoroutineFilter} with PIT (on by default). */
public final class KotlinCoroutineFilterFactory implements MutationInterceptorFactory {
    @Override
    public String description() {
        return "Kotlin coroutine state-machine mutants filter (etphonehome)";
    }

    @Override
    public Feature provides() {
        return Feature.named("FETPHCOROUTINE")
            .withOnByDefault(true)
            .withDescription("Filters mutants in the coroutine state-machine code kotlinc generates "
                + "(ResultKt.throwOnFailure resume checks, null for a Unit coroutine result)");
    }

    @Override
    public MutationInterceptor createInterceptor(InterceptorParameters params) {
        return new KotlinCoroutineFilter();
    }
}
