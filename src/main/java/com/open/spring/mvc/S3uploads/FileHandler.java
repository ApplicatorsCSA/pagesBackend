package com.open.spring.mvc.S3uploads;

public interface FileHandler {
    /**
     * Uploads a file (base64 encoded) to the storage system.
     * 
     * @param base64Data      Base64 encoded file content
     * @param filename        User provided filename
     * @param uid             User ID
     * @return The saved filename or null if failed
     */
    String uploadFile(String base64Data, String filename, String uid);

    /**
     * Retrieves a file and encodes it to base64.
     * 
     * @param uid             User ID
     * @param filename        Filename to retrieve
     * @return Base64 encoded string or null if failed
     */
    String decodeFile(String uid, String filename);

    /**
     * Deletes all files associated with a user.
     * 
     * @param uid             User ID
     * @return true if successful
     */
    boolean deleteFiles(String uid);
}
