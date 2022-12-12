/*
 *   sonic-agent  Agent of Sonic Cloud Real Machine Platform.
 *   Copyright (C) 2022 SonicCloudOrg
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as published
 *   by the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   You should have received a copy of the GNU Affero General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.cloud.sonic.agent.transport;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.automation.AndroidStepHandler;
import org.cloud.sonic.agent.automation.IOSStepHandler;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceLocalStatus;
import org.cloud.sonic.agent.bridge.ios.IOSDeviceLocalStatus;
import org.cloud.sonic.agent.bridge.ios.SibTool;
import org.cloud.sonic.agent.common.interfaces.DeviceStatus;
import org.cloud.sonic.agent.common.interfaces.PlatformType;
import org.cloud.sonic.agent.common.maps.AndroidDeviceManagerMap;
import org.cloud.sonic.agent.common.maps.AndroidPasswordMap;
import org.cloud.sonic.agent.common.maps.HandlerMap;
import org.cloud.sonic.agent.common.maps.IOSDeviceManagerMap;
import org.cloud.sonic.agent.tests.AndroidTests;
import org.cloud.sonic.agent.tests.IOSTests;
import org.cloud.sonic.agent.tests.SuiteListener;
import org.cloud.sonic.agent.tests.TaskManager;
import org.cloud.sonic.agent.tests.android.AndroidRunStepThread;
import org.cloud.sonic.agent.tests.android.AndroidTestTaskBootThread;
import org.cloud.sonic.agent.tests.ios.IOSRunStepThread;
import org.cloud.sonic.agent.tests.ios.IOSTestTaskBootThread;
import org.cloud.sonic.agent.tools.AgentManagerTool;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.PHCTool;
import org.cloud.sonic.agent.tools.SpringTool;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.testng.TestNG;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class TransportClient extends WebSocketClient {
    String host = String.valueOf(SpringTool.getPropertiesValue("sonic.agent.host"));
    String version = String.valueOf(SpringTool.getPropertiesValue("spring.version"));
    Integer port = Integer.valueOf(SpringTool.getPropertiesValue("sonic.agent.port"));
    Boolean isEnableAndroid = Boolean.valueOf(SpringTool.getPropertiesValue("modules.android.enable"));
    Boolean isEnableIOS = Boolean.valueOf(SpringTool.getPropertiesValue("modules.ios.enable"));

    public TransportClient(URI serverUri) {
        super(serverUri);
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        log.info("Connected and auth...");
    }

    @Override
    public void onMessage(String s) {
        JSONObject jsonObject = JSON.parseObject(s);
        if (jsonObject.getString("msg").equals("pong")) {
            return;
        }
        log.info("Agent <- Server message: {}", jsonObject);
        TransportWorker.cachedThreadPool.execute(() -> {
            switch (jsonObject.getString("msg")) {
                case "auth": {
                    if (jsonObject.getString("result").equals("pass")) {
                        log.info("server auth successful!");
                        BytesTool.agentId = jsonObject.getInteger("id");
                        BytesTool.highTemp = jsonObject.getInteger("highTemp");
                        BytesTool.highTempTime = jsonObject.getInteger("highTempTime");
                        BytesTool.agentHost = host;
                        TransportWorker.client = this;
                        JSONObject agentInfo = new JSONObject();
                        agentInfo.put("msg", "agentInfo");
                        agentInfo.put("agentId", BytesTool.agentId);
                        agentInfo.put("port", port);
                        agentInfo.put("version", "v" + version);
                        agentInfo.put("systemType", System.getProperty("os.name"));
                        agentInfo.put("host", host);
                        agentInfo.put("hasHub", PHCTool.isSupport() ? 1 : 0);
                        TransportWorker.client.send(agentInfo.toJSONString());
                        if (isEnableAndroid) {
                            IDevice[] iDevices = AndroidDeviceBridgeTool.getRealOnLineDevices();
                            for (IDevice d : iDevices) {
                                String status = AndroidDeviceManagerMap.getMap().get(d.getSerialNumber());
                                if (status != null) {
                                    AndroidDeviceLocalStatus.send(d.getSerialNumber(), status);
                                } else {
                                    AndroidDeviceLocalStatus.send(d.getSerialNumber(), d.getState() == null ? null : d.getState().toString());
                                }
                            }
                        }
                        if (isEnableIOS) {
                            List<String> udIds = SibTool.getDeviceList();
                            for (String u : udIds) {
                                String status = IOSDeviceManagerMap.getMap().get(u);
                                if (status != null) {
                                    IOSDeviceLocalStatus.send(u, status);
                                } else {
                                    IOSDeviceLocalStatus.send(u, DeviceStatus.ONLINE);
                                }
                            }
                        }
                    } else {
                        TransportWorker.isKeyAuth = false;
                        log.info("server auth failed!");
                    }
                    break;
                }
                case "shutdown": {
                    AgentManagerTool.stop();
                    break;
                }
                case "reboot":
                    if (jsonObject.getInteger("platform") == PlatformType.ANDROID) {
                        IDevice rebootDevice = AndroidDeviceBridgeTool.getIDeviceByUdId(jsonObject.getString("udId"));
                        if (rebootDevice != null) {
                            AndroidDeviceBridgeTool.reboot(rebootDevice);
                        }
                    }
                    if (jsonObject.getInteger("platform") == PlatformType.IOS) {
                        if (SibTool.getDeviceList().contains(jsonObject.getString("udId"))) {
                            SibTool.reboot(jsonObject.getString("udId"));
                        }
                    }
                    break;
                case "heartBeat":
                    JSONObject heartBeat = new JSONObject();
                    heartBeat.put("msg", "heartBeat");
                    heartBeat.put("status", "alive");
                    TransportWorker.send(heartBeat);
                    break;
                case "hub":
                    PHCTool.setPosition(jsonObject.getInteger("position"), jsonObject.getString("type"));
                    break;
                case "runStep":
                    if (jsonObject.getInteger("pf") == PlatformType.ANDROID) {
                        runAndroidStep(jsonObject);
                    }
                    if (jsonObject.getInteger("pf") == PlatformType.IOS) {
                        runIOSStep(jsonObject);
                    }
                    break;
                case "suite":
                    List<JSONObject> cases = jsonObject.getJSONArray("cases").toJavaList(JSONObject.class);
                    TestNG tng = new TestNG();
                    List<XmlSuite> suiteList = new ArrayList<>();
                    XmlSuite xmlSuite = new XmlSuite();
                    //bug?
                    for (JSONObject dataInfo : cases) {
                        XmlTest xmlTest = new XmlTest(xmlSuite);
                        Map<String, String> parameters = new HashMap<>();
                        parameters.put("dataInfo", dataInfo.toJSONString());
                        if (xmlSuite.getParameter("dataInfo") == null) {
                            xmlSuite.setParameters(parameters);
                        }
                        xmlTest.setParameters(parameters);
                        List<XmlClass> classes = new ArrayList<>();
                        if (jsonObject.getInteger("pf") == PlatformType.ANDROID) {
                            classes.add(new XmlClass(AndroidTests.class));
                        }
                        if (jsonObject.getInteger("pf") == PlatformType.IOS) {
                            classes.add(new XmlClass(IOSTests.class));
                        }
                        xmlTest.setXmlClasses(classes);
                    }
                    suiteList.add(xmlSuite);
                    tng.setXmlSuites(suiteList);
                    tng.addListener(new SuiteListener());
                    tng.run();
                    break;
                case "forceStopSuite":
                    List<JSONObject> caseList = jsonObject.getJSONArray("cases").toJavaList(JSONObject.class);
                    for (JSONObject aCase : caseList) {
                        int resultId = (int) aCase.get("rid");
                        int caseId = (int) aCase.get("cid");
                        JSONArray devices = (JSONArray) aCase.get("device");
                        List<JSONObject> deviceList = devices.toJavaList(JSONObject.class);
                        for (JSONObject device : deviceList) {
                            String udId = (String) device.get("udId");
                            TaskManager.forceStopSuite(jsonObject.getInteger("pf"), resultId, caseId, udId);
                        }
                    }
                    break;
            }
        });
    }

    @Override
    public void onClose(int i, String s, boolean b) {
        if (TransportWorker.isKeyAuth) {
            log.info("Server disconnected. Retry in 10s...");
        }
        if (TransportWorker.client == this) {
            TransportWorker.client = null;
        }
    }

    @Override
    public void onError(Exception e) {
        log.info(e.getMessage());
    }

    /**
     * Android 步骤调试
     */
    private void runAndroidStep(JSONObject jsonObject) {

        AndroidPasswordMap.getMap().put(jsonObject.getString("udId"), jsonObject.getString("pwd"));
        AndroidStepHandler androidStepHandler = HandlerMap.getAndroidMap().get(jsonObject.getString("sessionId"));
        if (androidStepHandler == null) {
            return;
        }
        androidStepHandler.resetResultDetailStatus();
        androidStepHandler.setGlobalParams(jsonObject.getJSONObject("gp"));

        AndroidTestTaskBootThread dataBean = new AndroidTestTaskBootThread(jsonObject, androidStepHandler);
        AndroidRunStepThread task = new AndroidRunStepThread(dataBean) {
            @Override
            public void run() {
                super.run();
                androidStepHandler.sendStatus();
            }
        };
        TaskManager.startChildThread(task.getName(), task);
    }

    /**
     * IOS步骤调试
     */
    private void runIOSStep(JSONObject jsonObject) {
        IOSStepHandler iosStepHandler = HandlerMap.getIOSMap().get(jsonObject.getString("sessionId"));
        if (iosStepHandler == null) {
            return;
        }
        iosStepHandler.resetResultDetailStatus();
        iosStepHandler.setGlobalParams(jsonObject.getJSONObject("gp"));

        IOSTestTaskBootThread dataBean = new IOSTestTaskBootThread(jsonObject, iosStepHandler);

        IOSRunStepThread task = new IOSRunStepThread(dataBean) {
            @Override
            public void run() {
                super.run();
                iosStepHandler.sendStatus();
            }
        };
        TaskManager.startChildThread(task.getName(), task);
    }
}
