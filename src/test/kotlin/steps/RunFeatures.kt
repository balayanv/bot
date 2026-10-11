package steps

import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/** Runs every .feature under src/test/resources/features (config in junit-platform.properties). */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
class RunFeatures
