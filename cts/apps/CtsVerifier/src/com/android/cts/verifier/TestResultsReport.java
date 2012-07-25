/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.cts.verifier;

import com.android.cts.verifier.TestListAdapter.TestListItem;

import org.xmlpull.v1.XmlSerializer;

import android.content.Context;
import android.os.Build;
import android.util.Xml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * XML text report of the current test results.
 * <p>
 * Sample:
 * <pre>
 * <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
 * <test-results-report report-version="1" creation-time="Tue Jun 28 11:04:10 PDT 2011">
 *   <verifier-info version-name="2.3_r4" version-code="2" />
 *   <device-info>
 *     <build-info fingerprint="google/soju/crespo:2.3.4/GRJ22/121341:user/release-keys" />
 *   </device-info>
 *   <test-results>
 *     <test title="Audio Quality Verifier" class-name="com.android.cts.verifier.audioquality.AudioQualityVerifierActivity" result="not-executed" />
 *     <test title="Hardware/Software Feature Summary" class-name="com.android.cts.verifier.features.FeatureSummaryActivity" result="fail" />
 *     <test title="Bluetooth Test" class-name="com.android.cts.verifier.bluetooth.BluetoothTestActivity" result="fail" />
 *     <test title="SUID File Scanner" class-name="com.android.cts.verifier.suid.SuidFilesActivity" result="not-executed" />
 *     <test title="Accelerometer Test" class-name="com.android.cts.verifier.sensors.AccelerometerTestActivity" result="pass" />
 *   </test-results>
 * </test-results-report>
 * </pre>
 */
class TestResultsReport {

    /** Version of the test report. Increment whenever adding new tags and attributes. */
    private static final int REPORT_VERSION = 1;

    /** Format of the report's creation time. Maintain the same format at CTS. */
    private static DateFormat DATE_FORMAT =
            new SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.ENGLISH);

    private static final String TEST_RESULTS_REPORT_TAG = "test-results-report";
    private static final String VERIFIER_INFO_TAG = "verifier-info";
    private static final String DEVICE_INFO_TAG = "device-info";
    private static final String BUILD_INFO_TAG = "build-info";
    private static final String TEST_RESULTS_TAG = "test-results";
    private static final String TEST_TAG = "test";

    private final Context mContext;

    private final TestListAdapter mAdapter;

    TestResultsReport(Context context, TestListAdapter adapter) {
        this.mContext = context;
        this.mAdapter = adapter;
    }

    String getType() {
        return "application/xml";
    }

    String getSubject() {
        return mContext.getString(R.string.subject_header,
                Version.getVersionName(mContext),
                Build.FINGERPRINT);
    }

    String getBody() throws IllegalArgumentException, IllegalStateException, IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        XmlSerializer xml = Xml.newSerializer();
        xml.setOutput(outputStream, "utf-8");
        xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        xml.startDocument("utf-8", true);

        xml.startTag(null, TEST_RESULTS_REPORT_TAG);
        xml.attribute(null, "report-version", Integer.toString(REPORT_VERSION));
        xml.attribute(null, "creation-time", DATE_FORMAT.format(new Date()));

        xml.startTag(null, VERIFIER_INFO_TAG);
        xml.attribute(null, "version-name", Version.getVersionName(mContext));
        xml.attribute(null, "version-code", Integer.toString(Version.getVersionCode(mContext)));
        xml.endTag(null, VERIFIER_INFO_TAG);

        xml.startTag(null, DEVICE_INFO_TAG);
        xml.startTag(null, BUILD_INFO_TAG);
        xml.attribute(null, "fingerprint", Build.FINGERPRINT);
        xml.endTag(null, BUILD_INFO_TAG);
        xml.endTag(null, DEVICE_INFO_TAG);

        xml.startTag(null, TEST_RESULTS_TAG);
        int count = mAdapter.getCount();
        for (int i = 0; i < count; i++) {
            TestListItem item = mAdapter.getItem(i);
            if (item.isTest()) {
                xml.startTag(null, TEST_TAG);
                xml.attribute(null, "title", item.title);
                xml.attribute(null, "class-name", item.className);
                xml.attribute(null, "result", getTestResultString(mAdapter.getTestResult(i)));
                xml.endTag(null, TEST_TAG);
            }
        }
        xml.endTag(null, TEST_RESULTS_TAG);

        xml.endTag(null, TEST_RESULTS_REPORT_TAG);
        xml.endDocument();

        return outputStream.toString("utf-8");
    }

    private String getTestResultString(int testResult) {
        switch (testResult) {
            case TestResult.TEST_RESULT_PASSED:
                return "pass";

            case TestResult.TEST_RESULT_FAILED:
                return "fail";

            case TestResult.TEST_RESULT_NOT_EXECUTED:
                return "not-executed";

            default:
                throw new IllegalArgumentException("Unknown test result: " + testResult);
        }
    }
}
