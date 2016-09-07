package cn.jpush.api.push;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cn.jpush.api.JPushClient;
import cn.jpush.api.common.connection.HttpProxy;
import cn.jpush.api.common.resp.APIConnectionException;
import cn.jpush.api.common.resp.APIRequestException;
import org.junit.Test;

import cn.jpush.api.BaseTest;
import cn.jpush.api.push.model.PushPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PushClientTest extends BaseTest {

    protected static final Logger LOG = LoggerFactory.getLogger(PushClientTest.class);

    @Test
    public void testSendPush() {
        // HttpProxy proxy = new HttpProxy("localhost", 3128);
        // Can use this https proxy: https://github.com/Exa-Networks/exaproxy
        JPushClient jpushClient = new JPushClient(MASTER_SECRET, APP_KEY, 3);

        // For push, all you need do is to build PushPayload object.
        PushPayload payload = buildPushObject_all_all_alert();

        try {
            PushResult result = jpushClient.sendPush(payload);
            LOG.info("Got result - " + result);

        } catch (APIConnectionException e) {
            LOG.error("Connection error. Should retry later. ", e);

        } catch (APIRequestException e) {
            LOG.error("Error response from JPush server. Should review and fix it. ", e);
            LOG.info("HTTP Status: " + e.getStatus());
            LOG.info("Error Code: " + e.getErrorCode());
            LOG.info("Error Message: " + e.getErrorMessage());
            LOG.info("Msg ID: " + e.getMsgId());
        }
    }

    public static PushPayload buildPushObject_all_all_alert() {
        return PushPayload.alertAll(ALERT);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_invalid_json() {
        PushClient pushClient = new PushClient(MASTER_SECRET, APP_KEY);
        
        try {
            pushClient.sendPush("{aaa:'a}");
        } catch (APIConnectionException e) {
            e.printStackTrace();
        } catch (APIRequestException e) {
            e.printStackTrace();
        }
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void test_empty_string() {
        PushClient pushClient = new PushClient(MASTER_SECRET, APP_KEY);
        
        try {
            pushClient.sendPush("");
        } catch (APIConnectionException e) {
            e.printStackTrace();
        } catch (APIRequestException e) {
            e.printStackTrace();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_empty_password() {
        new HttpProxy("127.0.0.1", 8080, "", null);
    }    

    @Test
    public void test_validate() {
    	PushClient pushClient = new PushClient(MASTER_SECRET, APP_KEY);
    	
    	try {
    		PushResult result = pushClient.sendPushValidate(PushPayload.alertAll("alert"));
    		assertTrue("", result.isResultOK());
    	} catch (APIRequestException e) {
    		fail("Should not fail");
    	} catch (APIConnectionException e) {
    		e.printStackTrace();
    	}
    }
    
    

}
