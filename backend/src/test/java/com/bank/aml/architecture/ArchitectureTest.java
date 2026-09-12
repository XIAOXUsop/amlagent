package com.bank.aml.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.bank.aml", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule HTTP_ADAPTERS_MUST_NOT_ACCESS_PERSISTENCE = noClasses().that()
        .resideInAnyPackage("..controller..", "..api..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..repository..", "..entity..")
        .because("HTTP 适配器只能通过应用服务访问持久化边界");

    @ArchTest
    static final ArchRule TOP_LEVEL_PACKAGES_MUST_BE_ACYCLIC = slices().matching("com.bank.aml.(*)..")
        .should()
        .beFreeOfCycles()
        .because("顶层业务包之间必须保持单向依赖");

    @ArchTest
    static final ArchRule FIELD_INJECTION_IS_FORBIDDEN = noFields().should()
        .beAnnotatedWith(Autowired.class)
        .because("依赖必须通过构造器显式注入");

    @ArchTest
    static final ArchRule IMPLICIT_SYSTEM_CLOCK_IS_FORBIDDEN = noClasses().should()
        .callMethod(LocalDateTime.class, "now")
        .orShould()
        .callMethod(Instant.class, "now")
        .because("生产代码必须注入 Clock，或在持久化实体边界显式使用 UTC Clock");

}
