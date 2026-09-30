package com.elibrary.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Executable architecture. Every rule here corresponds to a claim made in the README;
 * keeping them as tests is what stops those claims from quietly becoming false.
 */
@AnalyzeClasses(packages = "com.elibrary", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule theDomainKnowsNothingOfSpring =
            noClasses().that().resideInAPackage("..lending.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                    .because("the lending domain must stay plain Java so it is testable without a container");

    @ArchTest
    static final ArchRule theDomainKnowsNothingOfPersistence =
            noClasses().that().resideInAPackage("..lending.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..")
                    .because("persistence is an adapter concern; the aggregate is mapped, not annotated");

    @ArchTest
    static final ArchRule theDomainKnowsNothingOfTheCatalogue =
            noClasses().that().resideInAPackage("..lending.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("..catalog..")
                    .because("lending owns the BookInventory port; the catalogue implements it, not the reverse");

    @ArchTest
    static final ArchRule theDomainDoesNotReachOutwards =
            noClasses().that().resideInAPackage("..lending.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..lending.application..", "..lending.internal..", "..lending.web..")
                    .because("dependencies point inwards: the application layer orchestrates the domain, "
                            + "never the reverse");

    @ArchTest
    static final ArchRule lendingCannotReachIntoTheCatalogueInternals =
            noClasses().that().resideInAPackage("..lending..")
                    .should().dependOnClassesThat().resideInAPackage("..catalog.internal..")
                    .because("the catalogue's only public surface is com.elibrary.catalog");

    @ArchTest
    static final ArchRule theCatalogueCannotReachIntoLendingBeyondItsPort =
            noClasses().that().resideInAPackage("..catalog..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..lending.internal..", "..lending.application..", "..lending.web..")
                    .because("the catalogue may implement lending's port, but must not know its use cases");

    @ArchTest
    static final ArchRule sharedDependsOnNoModule =
            noClasses().that().resideInAPackage("..shared..")
                    .should().dependOnClassesThat().resideInAnyPackage("..lending..", "..catalog..", "..platform..")
                    .because("the shared kernel is the bottom of the dependency graph");

    @ArchTest
    static final ArchRule entitiesLiveOnlyInModuleInternals =
            noClasses().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                    .should().resideOutsideOfPackages("..catalog.internal..", "..lending.internal..")
                    .because("an entity that escapes its module can be serialised or mutated from anywhere");

    @ArchTest
    static final ArchRule springsPageNeverReachesTheWire =
            noClasses().that().resideInAPackage("..web..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.data.domain.Page")
                    .because("PageResult is the public pagination contract; Page's JSON shape is Spring's, not ours");

    @ArchTest
    static final ArchRule nobodyReadsTheWallClockDirectly =
            noClasses().should().callMethod(LocalDate.class, "now")
                    .orShould().callMethod(Instant.class, "now")
                    .orShould().callMethod(LocalDateTime.class, "now")
                    .because("time comes from the injected Clock so due dates and overdue are deterministic");
}
