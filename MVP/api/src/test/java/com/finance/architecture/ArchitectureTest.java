package com.finance.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

// v1.1 #1 — Layering rules enforced at test time. Scope: api's test classpath,
// which transitively pulls adapters + core. Worker lives in a separate module
// and is not scanned here.
@AnalyzeClasses(packages = "com.finance", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // Domain packages must compile without Spring on the classpath. Exact match on
    // `com.finance.service` (no `..`) skips com.finance.service.impl, which legitimately
    // lives in the adapters module and uses Spring.
    @ArchTest
    static final ArchRule core_packages_do_not_depend_on_spring =
            noClasses().that()
                    .resideInAnyPackage(
                            "com.finance.command..",
                            "com.finance.domain..",
                            "com.finance.prediction..",
                            "com.finance.query..",
                            "com.finance.service")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    // com.finance.exception is split: 16 domain exception classes in core, plus
    // GlobalExceptionHandler (@RestControllerAdvice) in api. The handler is the only
    // Spring-coupled class allowed in this package.
    @ArchTest
    static final ArchRule exception_package_no_spring_except_controller_advice =
            noClasses().that()
                    .resideInAPackage("com.finance.exception..")
                    .and().areNotAnnotatedWith(RestControllerAdvice.class)
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule rest_controllers_only_in_controller_package =
            classes().that().areAnnotatedWith(RestController.class)
                    .should().resideInAPackage("com.finance.controller..");

    @ArchTest
    static final ArchRule jpa_entities_only_in_entity_package =
            classes().that().areAnnotatedWith(Entity.class)
                    .should().resideInAPackage("com.finance.entity..");
}
