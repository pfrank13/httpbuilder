package groovyx.net.http.thirdparty

import groovy.util.slurpersupport.GPathResult;
import groovyx.net.http.HttpResponseException;
import groovyx.net.http.ParserRegistry;
import groovyx.net.http.thirdparty.GAEConnectionManager;

import static groovyx.net.http.Method.*
import static groovyx.net.http.ContentType.*

import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpParams;
import org.junit.Ignore
import org.junit.Test
import org.junit.Before

class GAETest {

    def http = null

    @Ignore
    @Test public void testURLFetchService() {
        http = newBuilder()
        http.uri = 'http://ajax.googleapis.com/ajax/services/search/web'
        http.request(GET) {
            uri.query = [ v:'1.0', q: 'HTTPBuilder' ]

            response.success = { resp, json ->
                println resp.statusLine
                json.responseData.results.each {
                    println "  ${it.titleNoFormatting} : ${it.visibleUrl}"
                }
            }
        }
    }


    def twitter = [ user: System.getProperty('twitter.user'),
                    consumerKey: System.getProperty('twitter.oauth.consumerKey'),
                    consumerSecret: System.getProperty('twitter.oauth.consumerSecret'),
                    accessToken: System.getProperty('twitter.oauth.accessToken'),
                    secretToken: System.getProperty('twitter.oauth.secretToken') ]

    /**
     * This method will parse the content based on the response content-type
     */
    @Test public void testGET() {
        def http = newBuilder('http://www.google.com')
        http.get( path:'/search', query:[q:'Groovy'],
                headers:['User-Agent':"Firefox"] ) { resp, html ->
            println "response status: ${resp.statusLine}"
            println "Content Type: ${resp.headers.'Content-Type'}"

            assert html
            assert html.HEAD.size() == 1
            assert html.HEAD.TITLE.size() == 1
            println "Title: ${html.HEAD.TITLE.text()}"
            assert html.BODY.size() == 1
        }
    }

    @Test public void testDefaultSuccessHandler() {
        def http = newBuilder('http://www.google.com')
        def html = http.request( GET ) {
            headers = ['User-Agent':"Firefox"]
            uri.path = '/search'
            uri.query = [q:'Groovy']
        }
        assert html instanceof GPathResult
        assert html.HEAD.size() == 1
        assert html.BODY.size() == 1

        // short form where GET takes no response handler.
        html = http.get( path:'/search', query:[q:'Groovy'] )
        assert html instanceof GPathResult
        assert html.HEAD.size() == 1
        assert html.BODY.size() == 1
    }

    @Test public void testSetHeaders() {
        def http = newBuilder('http://www.google.com')
        def val = '1'
        def v2 = 'two'
        def h3 = 'three'
        def h4 = 'four'
        http.headers = [one:"v$val", "$v2" : 2]
        http.headers.three = 'not Three'
        http.headers."$h3" = 'three'

        def request
        def html = http.request( GET ) { req ->
            assert headers.one == 'v1'
            assert headers.two == '2'
            assert headers.three == 'three'
            headers."$h4" = "$val"
            request = req
        }

        assert html
        def headers = request.allHeaders
        assert headers.find { it.name == 'one' && it.value == 'v1' }
        assert headers.find { it.name == 'two' && it.value == '2' }
        assert headers.find { it.name == 'three' && it.value == 'three' }
        assert headers.find { it.name == 'four' && it.value == '1' }
    }

    /* This tests a potential bug -- the reader is being accessed after the
     * request/response sequence has finished and response.consumeContent()
     * has already been called internally.  In practice, it appears that the
     * response data is being buffered, probably for any non-chunked responses.
     * A warning should probably be added, however, that the default response
     * handler will not work well with a chunked response if it is parsed as
     * TEXT or BINARY.
     */
    @Test public void testReaderWithDefaultResponseHandler() {
        def http = newBuilder('http://www.google.com')

        def reader = http.get( contentType:TEXT )

        assert reader instanceof Reader
        def out = new ByteArrayOutputStream()
        out << reader
        assert out.toString().length() > 0
//      println out.toString()
        assert out.toString().trim().endsWith( '</html>' )
    }

    @Test public void testDefaultFailureHandler() {
        def http = newBuilder('http://www.google.com')

        try {
            http.get( path:'/adsasf/kjsslkd' ) {
                assert false
            }
        }
        catch( HttpResponseException ex ) {
            assert ex.statusCode == 404
            assert ex.response.status == 404
            assert ex.response.headers
        }

    }

    /**
     * This method is similar to the above, but it will will parse the content
     * based on the given content-type, i.e. TEXT (text/plain).
     */
    @Test public void testReader() {
        def http = newBuilder('http://examples.oreilly.com')
        http.get( uri:'http://examples.oreilly.com/9780596002527/examples/first.xml',
                  contentType: TEXT, headers: [Accept:'*/*'] ) { resp, reader ->
            println "response status: ${resp.statusLine}"
            println 'Headers:'
            resp.headers.each {
                println "  ${it.name} : ${it.value}"
                assert it.name && it.value
            }
            println '------------------'

            assert reader instanceof Reader

            // we'll validate the reader by passing it to an XmlSlurper manually.

            def resolver = ParserRegistry.catalogResolver
            def parsedData = new XmlSlurper( entityResolver : resolver ).parse(reader)
            assert parsedData.children().size() > 0
        }
    }

    /* REST testing with Twitter!
     * Tests POST with XML response, and DELETE with a JSON response.
     */

    @Test public void testPOST() {
        def http = newBuilder('https://api.twitter.com/1.1/statuses/')

        http.auth.oauth twitter.consumerKey, twitter.consumerSecret,
                twitter.accessToken, twitter.secretToken

        def msg = "HTTPBuilder unit test was run on ${new Date()}"


        def postID = http.request( POST, JSON) { req ->
            uri.path = 'update.json'
            send URLENC, [status:msg,source:'httpbuilder']

             response.success = { resp, json ->
                println "Tweet response status: ${resp.statusLine}"
                assert resp.statusLine.statusCode == 200
                println "Content Length: ${resp.headers['Content-Length']?.value}"

                assert json.text == msg
                assert json.user.screen_name == twitter.user
                return json.id
            }
        }

        // delete the test message.
        Thread.sleep 5000
        http.request( POST, JSON ) { req ->
            uri.path = "destroy/${postID}.json"

            response.success = { resp, json ->
                assert json.id != null
                assert resp.statusLine.statusCode == 200
                println "Test tweet ID ${json.id} was deleted."
            }
        }
    }

    @Ignore // twitter is returning a 401 for unknown reasons here
    @Test public void testPlainURLEnc() {
        def http = newBuilder('https://api.twitter.com/1.1/statuses/')

        http.auth.oauth twitter.consumerKey, twitter.consumerSecret,
                twitter.accessToken, twitter.secretToken

        def msg = "HTTPBuilder's second unit test was run on ${new Date()}"

        def resp = http.post( contentType: JSON, path:'update.json',
                body:"status=$msg&source=httpbuilder" )

        def postID = resp.id.text()
        assert postID

        // delete the test message.
        Thread.sleep 5000
        http.request( DELETE, JSON ) {
            uri.path = "destroy/${postID}.json"
        }
    }

//  @Test
    public void testHeadMethod() {
        def http = newBuilder('https://api.twitter.com/1.1/statuses/')

        http.auth.oauth twitter.consumerKey, twitter.consumerSecret,
                twitter.accessToken, twitter.secretToken

        http.request( HEAD, XML ) {
            uri.path = 'friends_timeline.xml'

            response.success = { resp ->
                assert resp.getFirstHeader('Status').value == "200 OK"
                assert resp.headers.Status == "200 OK"
                assert resp.headers['Content-Encoding'].value == "gzip"
            }
        }
    }

    @Test public void testRequestAndDefaultResponseHandlers() {
        def http = newBuilder()

//       default response handlers.
        http.handler.'401' = { resp ->
            println 'access denied'
        }

        http.handler.failure = { resp ->
            println "Unexpected failure: ${resp.statusLine}"
        }

//       optional default URL for all actions:
        http.uri = 'http://www.google.com'

        http.request(GET,TEXT) { req ->
            response.success = { resp, stream ->
                println 'my response handler!'
                assert resp.statusLine.statusCode == 200
                println resp.statusLine
                //System.out << stream // print response stream
            }
        }
    }

    /**
     * Test a response handler that is assigned within a request config closure:
     */
    @Test public void test404() {
        newBuilder().request('http://www.google.com',GET,TEXT) {
            uri.path = '/asdfg/asasdfs' // should produce 404
            response.'404' = {
                println 'got expected 404!'
            }
            response.success = {
                assert false // should not have succeeded
            }
        }
    }

    /* http://googlesystem.blogspot.com/2008/04/google-search-rest-api.html
     * http://ajax.googleapis.com/ajax/services/search/web?v=1.0&q=Earth%20Day
     */
    @Ignore
    @Test public void testJSON() {

        def builder = newBuilder()

//      builder.parser.'text/javascript' = builder.parsers."$JSON"

        builder.request('http://ajax.googleapis.com',GET,JSON) {
            uri.path = '/ajax/services/search/web'
            uri.query = [ v:'1.0', q: 'Calvin and Hobbes' ]
            //UA header required to get Google to GZIP response:
            headers.'User-Agent' = "Mozilla/5.0 (X11; U; Linux x86_64; en-US; rv:1.9.0.4) Gecko/2008111319 Ubuntu/8.10 (intrepid) Firefox/3.0.4"
            response.success = { resp, json ->
                assert resp.headers['Content-Encoding'].value == "gzip"
                assert json.size() == 3
                //println json.responseData
                println "Query response: "
                json.responseData.results.each {
                    println "  ${it.titleNoFormatting} : ${it.visibleUrl}"
                }
                null
            }
        }
    }

    @Test public void testAuth() {
        def http = newBuilder( 'http://test.webdav.org' )

        /* The path issues a 404, but it does an auth challenge first. */
        http.handler.'404' = { println 'Auth successful' }

        http.request( GET, HTML ) {
            uri.path = '/auth-digest/'
            response.failure = { "expected failure" }
            response.success = {
                throw new AssertionError("request should have failed.")
            }
        }

        http.auth.basic( 'user2', 'user2' )

        http.request( GET, HTML ) {
            uri.path = '/auth-digest/'
        }

        http.request( GET, HTML ) {
            uri.path = '/auth-basic/'
        }
    }

    @Test public void testCatalog() {
        def http = newBuilder( 'http://weather.yahooapis.com/forecastrss' )

        http.parser.addCatalog getClass().getResource( '/rss-catalog.xml')
        def xml = http.get( query : [p:'02110',u:'f'] )


    }

    def newBuilder( uri ) {
        return new groovyx.net.http.HTTPBuilder(uri) {
            protected AbstractHttpClient getClient(HttpParams params) {
                return new DefaultHttpClient( new GAEConnectionManager(), params)
            }
        }
    }
}
