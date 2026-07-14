package com.example.finalproject.global.config;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

public class PostgisFunctionContributor implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        var functionRegistry = functionContributions.getFunctionRegistry();
        var typeRegistry = functionContributions.getTypeConfiguration().getBasicTypeRegistry();

        functionRegistry.registerPattern(
                "st_dwithin_geography",
                "st_dwithin(?1, cast(?2 as geography), ?3)",
                typeRegistry.resolve(StandardBasicTypes.BOOLEAN));
        functionRegistry.registerPattern(
                "st_distance_geography",
                "st_distance(?1, cast(?2 as geography))",
                typeRegistry.resolve(StandardBasicTypes.DOUBLE));
    }
}
