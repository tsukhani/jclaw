import org.junit.jupiter.api.*;
import play.test.*;
import play.mvc.*;
import play.mvc.Http.*;
import models.*;

class ApplicationTest extends FunctionalTest {

    @Test
    void testThatIndexPageWorks() {
        Response response = GET("/");
        assertIsOk(response);
        assertContentType("text/html", response);
        assertCharset(play.Play.defaultWebEncoding, response);
    }
    
}