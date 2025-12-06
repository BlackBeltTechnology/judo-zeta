package hu.blackbelt.judo.zeta.osgi.itest;

/*-
 * #%L
 * Judo :: Esm :: Model :: OSGi :: Integration Test
 * %%
 * Copyright (C) 2018 - 2022 BlackBelt Technology
 * %%
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * with the GNU Classpath Exception which is
 * available at https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 * #L%
 */

import org.slf4j.Logger;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.emf.common.util.URI;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import hu.blackbelt.osgi.utils.osgi.api.BundleTrackerManager;
import javax.inject.Inject;
import java.io.*;
import java.net.MalformedURLException;

import static hu.blackbelt.judo.zeta.osgi.itest.KarafFeatureProvider.karafConfig;
import static hu.blackbelt.judo.zeta.osgi.itest.KarafFeatureProvider.testTargetDir;
import static org.ops4j.pax.exam.CoreOptions.*;
import static org.ops4j.pax.exam.OptionUtils.combine;
import static org.ops4j.pax.tinybundles.core.TinyBundles.bundle;
import static org.ops4j.pax.tinybundles.core.TinyBundles.withBnd;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
@Slf4j
public class ZetaLoadITest {

    @Inject
    protected BundleTrackerManager bundleTrackerManager;

    @Inject
    BundleContext bundleContext;

    @Configuration
    public Option[] config() throws IOException {

        return combine(karafConfig(this.getClass()),
                mavenBundle(maven()
                        .groupId("hu.blackbelt.judo.zeta")
                        .artifactId("hu.blackbelt.judo.zeta.annotations")
                        .versionAsInProject()),

                mavenBundle(maven()
                        .groupId("hu.blackbelt.judo.zeta")
                        .artifactId("hu.blackbelt.judo.zeta.common")
                        .versionAsInProject()),

                mavenBundle(maven()
                        .groupId("hu.blackbelt.judo.zeta")
                        .artifactId("hu.blackbelt.judo.zeta.validation-core")
                        .versionAsInProject()),

                mavenBundle(maven()
                        .groupId("hu.blackbelt.judo.zeta")
                        .artifactId("hu.blackbelt.judo.zeta.transformation-core")
                        .versionAsInProject()));

    }

    @Test
    public void testModelValidation() throws Exception {
    }


}
