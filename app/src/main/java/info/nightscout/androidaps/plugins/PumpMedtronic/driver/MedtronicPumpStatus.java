package info.nightscout.androidaps.plugins.PumpMedtronic.driver;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.interfaces.PumpDescription;
import info.nightscout.androidaps.plugins.PumpCommon.data.PumpStatus;
import info.nightscout.androidaps.plugins.PumpCommon.defs.PumpType;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.RileyLinkConst;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.RileyLinkUtil;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.defs.RileyLinkTargetFrequency;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.defs.RileyLinkError;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.defs.RileyLinkServiceState;
import info.nightscout.androidaps.plugins.PumpMedtronic.defs.MedtronicDeviceType;
import info.nightscout.androidaps.plugins.PumpMedtronic.defs.PumpDeviceState;
import info.nightscout.androidaps.plugins.PumpMedtronic.util.MedtronicConst;
import info.nightscout.androidaps.plugins.PumpMedtronic.util.MedtronicUtil;
import info.nightscout.utils.SP;

/**
 * Created by andy on 4/28/18.
 */

public class MedtronicPumpStatus extends PumpStatus {

    private static Logger LOG = LoggerFactory.getLogger(MedtronicPumpStatus.class);

    public String errorDescription = null;
    public String serialNumber;
    //public PumpType pumpType = null;
    public String pumpFrequency = null;
    public String rileyLinkAddress = null;
    public Double maxBolus;
    public Double maxBasal;
    private String[] frequencies;
    private boolean isFrequencyUS = false;

    public boolean inPreInit = true;

    String regexMac = "([\\da-fA-F]{1,2}(?:\\:|$)){6}";
    String regexSN = "[0-9]{6}";

    private Map<String, PumpType> medtronicPumpMap = null;
    private Map<String, MedtronicDeviceType> medtronicDeviceTypeMap = null;

    // statuses
    public RileyLinkServiceState rileyLinkServiceState = RileyLinkServiceState.NotStarted;
    public RileyLinkError rileyLinkError;
    public PumpDeviceState pumpDeviceState = PumpDeviceState.NeverContacted;

    public MedtronicDeviceType medtronicDeviceType = null;


    // fixme


    public double currentBasal = 0;

    public int tempBasalInProgress = 0;
    public int tempBasalRatio = 0;
    public int tempBasalRemainMin = 0;
    public Date tempBasalStart;
    public Double tempBasalAmount = 0.0d;
    public Integer tempBasalLength = 0;


    // fixme


    public MedtronicPumpStatus(PumpDescription pumpDescription) {
        super(pumpDescription);
    }


    @Override
    public void initSettings() {

        this.activeProfileName = "STD";
        this.reservoirRemainingUnits = 75d;
        this.batteryRemaining = 75;

        if (this.medtronicPumpMap == null)
            createMedtronicPumpMap();

        if (this.medtronicDeviceTypeMap == null)
            createMedtronicDeviceTypeMap();

        this.lastConnection = SP.getLong(MedtronicConst.Statistics.LastGoodPumpCommunicationTime, 0L);
        Date d = new Date();
        d.setTime(this.lastConnection);

        this.lastDataTime = d;
    }

    private void createMedtronicDeviceTypeMap() {
        medtronicDeviceTypeMap = new HashMap<>();
        medtronicDeviceTypeMap.put("512", MedtronicDeviceType.Medtronic_512);
        medtronicDeviceTypeMap.put("712", MedtronicDeviceType.Medtronic_712);
        medtronicDeviceTypeMap.put("515", MedtronicDeviceType.Medtronic_515);
        medtronicDeviceTypeMap.put("715", MedtronicDeviceType.Medtronic_715);

        medtronicDeviceTypeMap.put("522", MedtronicDeviceType.Medtronic_522);
        medtronicDeviceTypeMap.put("722", MedtronicDeviceType.Medtronic_722);
        medtronicDeviceTypeMap.put("523", MedtronicDeviceType.Medtronic_523_Revel);
        medtronicDeviceTypeMap.put("723", MedtronicDeviceType.Medtronic_723_Revel);
        medtronicDeviceTypeMap.put("554", MedtronicDeviceType.Medtronic_554_Veo);
        medtronicDeviceTypeMap.put("754", MedtronicDeviceType.Medtronic_754_Veo);
    }


    private void createMedtronicPumpMap() {

        medtronicPumpMap = new HashMap<>();
        medtronicPumpMap.put("512", PumpType.Minimed_512_712);
        medtronicPumpMap.put("712", PumpType.Minimed_512_712);
        medtronicPumpMap.put("515", PumpType.Minimed_515_715);
        medtronicPumpMap.put("715", PumpType.Minimed_515_715);

        medtronicPumpMap.put("522", PumpType.Minimed_522_722);
        medtronicPumpMap.put("722", PumpType.Minimed_522_722);
        medtronicPumpMap.put("523", PumpType.Minimed_523_723);
        medtronicPumpMap.put("723", PumpType.Minimed_523_723);
        medtronicPumpMap.put("554", PumpType.Minimed_554_754_Veo);
        medtronicPumpMap.put("754", PumpType.Minimed_554_754_Veo);

        frequencies = new String[2];
        frequencies[0] = MainApp.gs(R.string.medtronic_pump_frequency_us);
        frequencies[1] = MainApp.gs(R.string.medtronic_pump_frequency_worldwide);
    }


    boolean serialChanged = false;
    boolean rileyLinkAddressChanged = false;

    public void verifyConfiguration() {
        try {

            // FIXME don't reload information several times
            if (this.medtronicPumpMap == null)
                createMedtronicPumpMap();

            if (this.medtronicDeviceTypeMap == null)
                createMedtronicDeviceTypeMap();

            String serialNr = SP.getString(MedtronicConst.Prefs.PumpSerial, null);

            if (serialNr == null) {
                this.errorDescription = MainApp.gs(R.string.medtronic_error_serial_not_set);
                return;
            } else {
                if (!serialNr.matches(regexSN)) {
                    this.errorDescription = MainApp.gs(R.string.medtronic_error_serial_invalid);
                    return;
                } else {
                    if (!serialNr.equals(this.serialNumber)) {
                        this.serialNumber = serialNr;
                        serialChanged = true;
                    }
                }
            }


            String pumpType = SP.getString(MedtronicConst.Prefs.PumpType, null);

            if (pumpType == null) {
                this.errorDescription = MainApp.gs(R.string.medtronic_error_pump_type_not_set);
                return;
            } else {
                String pumpTypePart = pumpType.substring(0, 3);

                if (!pumpTypePart.matches("[0-9]{3}")) {
                    this.errorDescription = MainApp.gs(R.string.medtronic_error_pump_type_invalid);
                    return;
                } else {
                    this.pumpType = medtronicPumpMap.get(pumpTypePart);
                    this.medtronicDeviceType = medtronicDeviceTypeMap.get(pumpTypePart);

                    if (pumpTypePart.startsWith("7"))
                        this.reservoirFullUnits = "300";
                    else
                        this.reservoirFullUnits = "176";
                }
            }


            String pumpFrequency = SP.getString(MedtronicConst.Prefs.PumpFrequency, null);

            if (pumpFrequency == null) {
                this.errorDescription = MainApp.gs(R.string.medtronic_error_pump_frequency_not_set);
                return;
            } else {
                if (!pumpFrequency.equals(frequencies[0]) && !pumpFrequency.equals(frequencies[1])) {
                    this.errorDescription = MainApp.gs(R.string.medtronic_error_pump_frequency_invalid);
                    return;
                } else {
                    this.pumpFrequency = pumpFrequency;
                    this.isFrequencyUS = pumpFrequency.equals(frequencies[0]);

                    RileyLinkUtil.setRileyLinkTargetFrequency(this.isFrequencyUS ? //
                            RileyLinkTargetFrequency.Medtronic_US : RileyLinkTargetFrequency.Medtronic_WorldWide);
                }
            }


            String rileyLinkAddress = SP.getString(RileyLinkConst.Prefs.RileyLinkAddress, null);

            if (rileyLinkAddress == null) {
                this.errorDescription = MainApp.gs(R.string.medtronic_error_rileylink_address_invalid);
                return;
            } else {
                if (!rileyLinkAddress.matches(regexMac)) {
                    this.errorDescription = MainApp.gs(R.string.medtronic_error_rileylink_address_invalid);
                } else {
                    if (!rileyLinkAddress.equals(this.rileyLinkAddress)) {
                        this.rileyLinkAddress = rileyLinkAddress;
                        rileyLinkAddressChanged = true;
                    }
                }
            }


            maxBolus = checkParameterValue(MedtronicConst.Prefs.MaxBolus, "25.0", 25.0d);

            maxBasal = checkParameterValue(MedtronicConst.Prefs.MaxBasal, "35.0", 35.0d);

            startService();

        } catch (Exception ex) {
            this.errorDescription = ex.getMessage();
            LOG.error("Error on Verification: " + ex.getMessage(), ex);
        }
    }

    private boolean startService() {

        if (serialChanged && !inPreInit && MedtronicUtil.getMedtronicService() != null) {
            MedtronicUtil.getMedtronicService().setPumpIDString(this.serialNumber); // short operation
            serialChanged = false;
        }

        if (rileyLinkAddressChanged && !inPreInit && MedtronicUtil.getMedtronicService() != null) {
            MedtronicUtil.sendBroadcastMessage(RileyLinkConst.Intents.RileyLinkNewAddressSet);
            rileyLinkAddressChanged = false;
        }

        return (rileyLinkAddressChanged == false && serialChanged == false);
    }


    private double checkParameterValue(String key, String defaultValue, double defaultValueDouble) {
        double val = 0.0d;

        String value = SP.getString(key, defaultValue);

        try {
            val = Double.parseDouble(value);
        } catch (Exception ex) {
            LOG.error("Error parsing setting: {}, value found {}", key, value);
            val = defaultValueDouble;
        }

        if (val > defaultValueDouble) {
            SP.putString(MedtronicConst.Prefs.MaxBolus, "25.0");
            val = defaultValueDouble;
        }

        return val;
    }


    public String getErrorInfo() {
        verifyConfiguration();

        return (this.errorDescription == null) ? "-" : this.errorDescription;
    }


    @Override
    public void refreshConfiguration() {
        verifyConfiguration();
    }


    public boolean setNotInPreInit() {
        this.inPreInit = false;

        return startService();
    }


    public double getBasalProfileForHour() {
        if (basalsByHour != null) {
            GregorianCalendar c = new GregorianCalendar();
            int hour = c.get(Calendar.HOUR_OF_DAY);

            return basalsByHour[hour];
        }

        return 0;
    }

}