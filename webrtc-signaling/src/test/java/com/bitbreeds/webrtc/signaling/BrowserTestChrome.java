package com.bitbreeds.webrtc.signaling;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.apache.camel.CamelContext;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;

/**
 * Copyright (c) 27/06/16, Jonas Waage
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
public class BrowserTestChrome {

    @Test
    public void testFull() throws Exception {
        System.setProperty("com.bitbreeds.keystore", "./src/test/resources/ws2.jks");
        System.setProperty("com.bitbreeds.keystore.alias", "websocket");
        System.setProperty("com.bitbreeds.keystore.pass", "websocket");

        SimpleSignalingExample.main();

        File fl = new File(".././web/index.html");

        String url = "file://" + fl.getAbsolutePath();
        System.out.println(url);
        WebDriverManager.chromedriver().setup();
        WebDriver driver = new ChromeDriver();
        driver.get(url);

        (new WebDriverWait(driver, 20)).until(
                (ExpectedCondition<Boolean>) d -> {
                    assert d != null;
                    return d.findElement(By.id("status")).getText().equalsIgnoreCase("ONMESSAGE");
                }
        );

        driver.quit();

    }

    @Test
    public void testAllMessages() throws Exception {
        System.setProperty("com.bitbreeds.keystore", "./src/test/resources/ws2.jks");
        System.setProperty("com.bitbreeds.keystore.alias", "websocket");
        System.setProperty("com.bitbreeds.keystore.pass", "websocket");

        CamelContext ctx = SimpleSignalingExample.camelContext();
        ctx.start();

        File fl = new File(".././web/transfer.html");

        String url = "file://" + fl.getAbsolutePath();
        System.out.println(url);
        WebDriverManager.chromedriver().setup();
        WebDriver driver = new ChromeDriver();
        driver.get(url);

        (new WebDriverWait(driver, 20)).until(
                (ExpectedCondition<Boolean>) d -> {
                    assert d != null;
                    return d.findElement(By.id("all-received")).getText().equalsIgnoreCase("ALL RECEIVED");
                }
        );

        driver.quit();

        ctx.stop();

    }



}
