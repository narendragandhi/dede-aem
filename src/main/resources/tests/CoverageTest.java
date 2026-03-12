package com.dede.test;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.apache.sling.caconfig.annotation.Configuration;
import javax.inject.Inject;

@Component(service = MyService.class)
@Designate(ocd = MyConfig.class)
@SlingServletResourceTypes(resourceTypes = "demo/res")
@Model(adaptables = org.apache.sling.api.resource.Resource.class, resourceType = "demo/model")
@Configuration(label = "Demo CAConfig")
public class CoverageTest implements MyService {

    @Reference
    private AnotherService anotherService;

    @Inject
    @OSGiService
    private ThirdService thirdService;

    @Reference
    public void bindService(FourthService s) {}

    @ObjectClassDefinition(name = "My Config")
    public @interface MyConfig {}

    public interface MyService {}
    public interface AnotherService {}
    public interface ThirdService {}
    public interface FourthService {}
}
