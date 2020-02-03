/**
 *
 */
package io.jenkins.plugins.intotorecorder.transport;

import java.io.IOException;

import io.github.in_toto.models.Link;

import java.net.URI;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;


public class GenericCRUD extends Transport {

    URI uri;

    public void sendRequest(URI uri, String metadata) {
        try {
            HttpRequest request = new NetHttpTransport()
                .createRequestFactory()
                .buildPostRequest(new GenericUrl(uri),
                    ByteArrayContent.fromString("application/x-www-form-uriencoded",
                        metadata));
            HttpResponse response = request.execute();
            System.out.println(response.parseAsString());

            /* FIXME: should handle error codes and other situations more appropriately,
             * but this gets the job done for a PoC
             */
        } catch (IOException e) {
            throw new RuntimeException("couldn't serialize to HTTP server: " + e);
        }
    }

    public GenericCRUD(URI uri) {
        this.uri = uri;
    }

    public void submit(Link link) {
        // try {
        //     HttpRequest request = new NetHttpTransport()
        //         .createRequestFactory()
        //         .buildPostRequest(new GenericUrl(this.uri),
        //             ByteArrayContent.fromString("application/x-www-form-uriencoded",
        //                 link.dumpString()));
        //     HttpResponse response = request.execute();
        //     System.out.println(response.parseAsString());

        //     /* FIXME: should handle error codes and other situations more appropriately,
        //      * but this gets the job done for a PoC
        //      */
        // } catch (IOException e) {
        //     throw new RuntimeException("couldn't serialize to HTTP server: " + e);
        // }
        this.sendRequest(this.uri, link.dumpString());
    }
}
