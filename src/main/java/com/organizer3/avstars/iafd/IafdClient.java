package com.organizer3.avstars.iafd;

/**
 * Thin HTTP transport for IAFD pages.
 *
 * <p>Returns raw HTML bodies. Callers (parsers, commands) handle all parsing.
 * The production implementation is {@link HttpIafdClient}; tests inject a fake.
 */
public interface IafdClient {

    /**
     * Fetches the search results page for the given performer name.
     * URL: {@code https://www.iafd.com/ramesearch.asp?searchtype=comprehensive&searchstring=<name>}
     *
     * @param name performer name (URL-encoded by the implementation)
     * @return raw HTML body
     * @throws IafdFetchException if the HTTP request fails
     */
    String fetchSearch(String name);

    /**
     * Fetches the performer profile page for the given IAFD UUID.
     * URL: {@code https://www.iafd.com/person.rme/id=<uuid>}
     *
     * @param iafdId UUID string from IAFD (e.g. {@code 53696199-bf71-4219-b58a-bd1e2fae9f1e})
     * @return raw HTML body
     * @throws IafdFetchException if the HTTP request fails
     */
    String fetchProfile(String iafdId);

    /**
     * Downloads a binary resource (headshot image) and returns the raw bytes.
     *
     * @param url absolute URL of the image
     * @return image bytes
     * @throws IafdFetchException if the download fails
     */
    byte[] fetchBytes(String url);
}
