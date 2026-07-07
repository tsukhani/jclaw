import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import play.mvc.Http.Response;

class ApplicationTest extends FunctionalTest {

    @Test
    void testThatIndexPageWorks() {
        Response response = GET("/");
        assertIsOk(response);
        assertContentType("text/html", response);
        assertCharset(play.Play.defaultWebEncoding, response);
    }
    
}