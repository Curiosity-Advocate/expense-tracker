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

    private static final String BANKINTEGRATION_PKG = "com.finance.bankintegration..";

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

    // v1.0 controllers live in com.finance.controller. The bankintegration
    // module owns its own controllers under com.finance.bankintegration.controller
    // so the module stays self-contained.
    @ArchTest
    static final ArchRule rest_controllers_only_in_allowed_packages =
            classes().that().areAnnotatedWith(RestController.class)
                    .should().resideInAnyPackage(
                            "com.finance.controller..",
                            BANKINTEGRATION_PKG);

    // Original v1.0 entities live in com.finance.entity. B1 entities live
    // inside the isolated bankintegration module's own entity sub-package
    // so the bank-integration tree is self-contained — see the
    // bankintegration_is_internally_sealed rule below.
    @ArchTest
    static final ArchRule jpa_entities_only_in_allowed_packages =
            classes().that().areAnnotatedWith(Entity.class)
                    .should().resideInAnyPackage(
                            "com.finance.entity..",
                            BANKINTEGRATION_PKG);

    // B1 — bank-integration module isolation. Nothing outside
    // com.finance.bankintegration.. may import from inside it. This is the
    // compile-checked seal that lets us swap source implementations (CSV
    // now, Basiq in v3.0) without touching any consumer code, and lets us
    // delete a source path entirely by removing its sub-package.
    @ArchTest
    static final ArchRule bankintegration_is_internally_sealed =
            noClasses().that()
                    .resideOutsideOfPackage(BANKINTEGRATION_PKG)
                    .should().dependOnClassesThat()
                    .resideInAPackage(BANKINTEGRATION_PKG);
}
