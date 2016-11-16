import com.github.tomakehurst.wiremock.common.Slf4jNotifier;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.netflix.loadbalancer.BaseLoadBalancer;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.Server;
import com.opentable.extension.BodyTransformer;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.boot.test.TestRestTemplate;
import org.springframework.boot.test.WebIntegrationTest;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.netflix.feign.EnableFeignClients;
import org.springframework.cloud.netflix.feign.FeignClient;
import org.springframework.cloud.netflix.ribbon.RibbonClient;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.SpanReporter;
import org.springframework.cloud.sleuth.instrument.rxjava.RxJavaAutoConfiguration;
import org.springframework.cloud.sleuth.util.ArrayListSpanAccumulator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = {FeignClientServerErrorTest.TestConfiguration.class})
@WebIntegrationTest(value = "spring.application.name=fooservice")
public class FeignClientServerErrorTest {

    private static final int FEIGN_PORT = 12000;

    private RestTemplate restTemplate = new TestRestTemplate();

    @Autowired
    private ArrayListSpanAccumulator arrayListSpanAccumulator;

    @ClassRule
    public static WireMockRule wireMockRule = new WireMockRule(
            wireMockConfig()
                    .port(FEIGN_PORT)
                    .notifier(new Slf4jNotifier(true))
                    .extensions(new BodyTransformer()));

    @Before
    public void setUp() throws Exception {
        arrayListSpanAccumulator.getSpans().clear();
    }

    @Test
    public void test_feignReturnsVoid() throws Exception {
        wireMockRule.stubFor(post(urlEqualTo("/test/void"))
                .willReturn(aResponse()
                        .withStatus(200)));

        ResponseEntity<ResponseEntity> entity = restTemplate.getForEntity("http://localhost:9099/test/void", ResponseEntity.class);
        assertThat(entity.getStatusCode(), is(HttpStatus.OK));

        wireMockRule.verify(postRequestedFor(urlEqualTo("/test/void")));

        List<Span> spans = arrayListSpanAccumulator.getSpans();
        assertThat(spans.size(), is(3));
    }

    @Test
    public void test_feignReturnsValue() throws Exception {
        wireMockRule.stubFor(post(urlEqualTo("/test"))
                .willReturn(aResponse()
                        .withStatus(200)));

        ResponseEntity<ResponseEntity> entity = restTemplate.getForEntity("http://localhost:9099/test", ResponseEntity.class);
        assertThat(entity.getStatusCode(), is(HttpStatus.OK));

        wireMockRule.verify(postRequestedFor(urlEqualTo("/test")));

        List<Span> spans = arrayListSpanAccumulator.getSpans();
        assertThat(spans.size(), is(3));
    }

    @RestController
    public static class FooController {

        @Autowired
        private TestFeignInterface testFeignInterface;

        @RequestMapping(value = "/test/void", method = RequestMethod.GET)
        public ResponseEntity<String> feignVoidCall() {
            testFeignInterface.voidTest();
            return new ResponseEntity<>(HttpStatus.OK);
        }

        @RequestMapping(value = "/test", method = RequestMethod.GET)
        public ResponseEntity<String> feignCall() {
            ResponseEntity<String> test = testFeignInterface.test();
            return new ResponseEntity<>(HttpStatus.OK);
        }

    }

    @FeignClient(value = "fooservice")
    public interface TestFeignInterface {

        @RequestMapping(method = RequestMethod.POST, value = "/test/void")
        void voidTest();

        @RequestMapping(method = RequestMethod.POST, value = "/test")
        ResponseEntity<String> test();
    }

    @Configuration
    @EnableAutoConfiguration(exclude = RxJavaAutoConfiguration.class)
    @EnableFeignClients
    @RibbonClient(value = "fooservice", configuration = SimpleRibbonClientConfiguration.class)
    public static class TestConfiguration {

        @Bean
        FooController fooController() {
            return new FooController();
        }

        @LoadBalanced
        @Bean
        public RestTemplate restTemplate() {
            return new RestTemplate();
        }

        @Bean
        public SpanReporter spanReporter() {
            return new ArrayListSpanAccumulator();
        }

    }

    @Configuration
    public static class SimpleRibbonClientConfiguration {

        @Bean
        public ILoadBalancer ribbonLoadBalancer() {
            BaseLoadBalancer balancer = new BaseLoadBalancer();
            balancer.setServersList(Collections.singletonList(new Server("localhost", FEIGN_PORT)));
            return balancer;
        }

    }

}
