/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package ucar.nc2.iosp.cinrad;

import ucar.ma2.*;
import ucar.nc2.*;
import ucar.nc2.constants.*;
import ucar.nc2.iosp.nexrad2.NexradStationDB;
import ucar.nc2.iosp.AbstractIOServiceProvider;
import ucar.nc2.units.DateFormatter;
import ucar.nc2.util.CancelTask;
import ucar.unidata.io.RandomAccessFile;
import java.io.IOException;
import java.util.*;

/**
 * An IOServiceProvider for CINRAD level II files.
 *
 *
 * @author caron
 */
public class Cinrad2IOServiceProvider extends AbstractIOServiceProvider {
  private static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Cinrad2IOServiceProvider.class);
  private static final int MISSING_INT = -9999;
  private static final float MISSING_FLOAT = Float.NaN;
  public static boolean isSC = false;
  public static boolean isCC = false;
  public static boolean isCC20 = false;

  public boolean isValidFileOld(RandomAccessFile raf) {
    try {
      String loc = raf.getLocation();
      int posFirst = loc.lastIndexOf('/') + 1;
      if (posFirst < 0)
        posFirst = 0;
      String stationId = loc.substring(posFirst, posFirst + 4);
      NexradStationDB.init();
      NexradStationDB.Station station = NexradStationDB.get("K" + stationId);
      return station != null;
    } catch (IOException ioe) {
      return false;
    }
  }

  public boolean isValidFile(RandomAccessFile raf) {
    try {
      if (isCINRAD(raf)) {
        Cinrad2Record.MISSING_DATA = (byte) 0;
        return true;
      } else {
        Cinrad2Record.MISSING_DATA = (byte) 1;
      }
      raf.order(RandomAccessFile.LITTLE_ENDIAN);
      raf.seek(0);
      raf.skipBytes(14);
      short message_type = raf.readShort();
      if (message_type != 1)
        return false;

      raf.skipBytes(12);
      // data header
      byte[] b4 = raf.readBytes(4);
      int data_msecs = bytesToInt(b4, true);
      byte[] b2 = raf.readBytes(2);
      short data_julian_date = (short) bytesToShort(b2, true);
      if (data_msecs > 86400000)
        return false;
      java.util.Date dd = Cinrad2Record.getDate(data_julian_date, data_msecs);

      Calendar cal = new GregorianCalendar(new SimpleTimeZone(0, "GMT"));
      cal.clear();
      cal.setTime(dd);
      int year = cal.get(Calendar.YEAR);
      cal.setTime(new Date());
      int cyear = cal.get(Calendar.YEAR);
      return year >= 1990 && year <= cyear;
    } catch (IOException ioe) {
      return false;
    }

  }

  public boolean isCINRAD(RandomAccessFile raf) {
    int data_msecs = 0;

    try {
      raf.order(RandomAccessFile.LITTLE_ENDIAN);
      raf.seek(0);

      byte[] b128 = raf.readBytes(136);
      String radarT = new String(b128);

      if (radarT.contains("CINRAD/SC") || radarT.contains("CINRAD/CD")) {
        isSC = true;
        isCC = false;
        isCC20 = false;
        return true;
      } else if (radarT.contains("CINRADC")) {
        isCC = true;
        isSC = false;
        isCC20 = false;
        return true;
      } else if (!radarT.contains("CINRADC") && radarT.contains("CINRAD/CC")) {
        isCC20 = true;
        isSC = false;
        isCC = false;
        return true;
      } else {
        isSC = false;
        isCC = false;
        isCC20 = false;
        return false;
      }
    } catch (IOException ioe) {
      return false;
    }

  }


  public static int bytesToInt(byte[] bytes, boolean swapBytes) {
    byte a = bytes[0];
    byte b = bytes[1];
    byte c = bytes[2];
    byte d = bytes[3];
    if (swapBytes) {
      return ((a & 0xff)) + ((b & 0xff) << 8) + ((c & 0xff) << 16) + ((d & 0xff) << 24);
    } else {
      return ((a & 0xff) << 24) + ((b & 0xff) << 16) + ((c & 0xff) << 8) + ((d & 0xff));
    }
  }

  public static int bytesToShort(byte[] bytes, boolean swapBytes) {
    byte a = bytes[0];
    byte b = bytes[1];

    if (swapBytes) {
      return ((a & 0xff)) + ((b & 0xff) << 8);

    } else {
      return ((a & 0xff) << 24) + ((b & 0xff) << 16);

    }
  }


  public String getFileTypeId() {
    return "CINRAD";
  }

  public String getFileTypeDescription() {
    return "Chinese Level-II Base Data";
  }

  private Cinrad2VolumeScan volScan;
  private Dimension radialDim;
  private double radarRadius;
  private DateFormatter formatter = new DateFormatter();

  public void open(RandomAccessFile raf, NetcdfFile ncfile, CancelTask cancelTask) throws IOException {
    super.open(raf, ncfile, cancelTask);
    NexradStationDB.init();

    volScan = new Cinrad2VolumeScan(raf, cancelTask); // raf may change if uncompressed
    this.raf = volScan.raf;
    this.location = volScan.raf.getLocation();

    if (volScan.hasDifferentDopplarResolutions())
      throw new IllegalStateException("volScan.hasDifferentDopplarResolutions");
    // if(isCC20)
    radialDim = new Dimension("radial", volScan.getMinRadials());
    // else
    // radialDim = new Dimension("radial", volScan.getMaxRadials());
    ncfile.addDimension(null, radialDim);

    makeVariable(ncfile, Cinrad2Record.REFLECTIVITY, "Reflectivity", "Reflectivity", "R",
        volScan.getReflectivityGroups());
    int velocity_type =
        (volScan.getDopplarResolution() == Cinrad2Record.DOPPLER_RESOLUTION_HIGH_CODE) ? Cinrad2Record.VELOCITY_HI
            : Cinrad2Record.VELOCITY_LOW;
    Variable v =
        makeVariable(ncfile, velocity_type, "RadialVelocity", "Radial Velocity", "V", volScan.getVelocityGroups());
    makeVariableNoCoords(ncfile, Cinrad2Record.SPECTRUM_WIDTH, "SpectrumWidth", "Spectrum Width", v);

    if (volScan.getStationId() != null) {
      ncfile.addAttribute(null, new Attribute("Station", volScan.getStationId()));
      ncfile.addAttribute(null, new Attribute("StationName", volScan.getStationName()));
      ncfile.addAttribute(null, new Attribute("StationLatitude", volScan.getStationLatitude()));
      ncfile.addAttribute(null, new Attribute("StationLongitude", volScan.getStationLongitude()));
      ncfile.addAttribute(null, new Attribute("StationElevationInMeters", volScan.getStationElevation()));

      double latRadiusDegrees = Math.toDegrees(radarRadius / ucar.unidata.geoloc.Earth.getRadius());
      ncfile.addAttribute(null, new Attribute("geospatial_lat_min", volScan.getStationLatitude() - latRadiusDegrees));
      ncfile.addAttribute(null, new Attribute("geospatial_lat_max", volScan.getStationLatitude() + latRadiusDegrees));
      double cosLat = Math.cos(Math.toRadians(volScan.getStationLatitude()));
      double lonRadiusDegrees = Math.toDegrees(radarRadius / cosLat / ucar.unidata.geoloc.Earth.getRadius());
      ncfile.addAttribute(null, new Attribute("geospatial_lon_min", volScan.getStationLongitude() - lonRadiusDegrees));
      ncfile.addAttribute(null, new Attribute("geospatial_lon_max", volScan.getStationLongitude() + lonRadiusDegrees));


      // add a radial coordinate transform (experimental)
      Variable ct = new Variable(ncfile, null, null, "radialCoordinateTransform");
      ct.setDataType(DataType.CHAR);
      ct.setDimensions(""); // scalar
      ct.addAttribute(new Attribute("transform_name", "Radial"));
      ct.addAttribute(new Attribute("center_latitude", volScan.getStationLatitude()));
      ct.addAttribute(new Attribute("center_longitude", volScan.getStationLongitude()));
      ct.addAttribute(new Attribute("center_elevation", volScan.getStationElevation()));
      ct.addAttribute(new Attribute(_Coordinate.TransformType, "Radial"));
      ct.addAttribute(new Attribute(_Coordinate.AxisTypes, "RadialElevation RadialAzimuth RadialDistance"));

      Array data = Array.factory(DataType.CHAR, new int[0], new char[] {' '});
      ct.setCachedData(data, true);
      ncfile.addVariable(null, ct);
    }

    DateFormatter formatter = new DateFormatter();

    ncfile.addAttribute(null, new Attribute(CDM.CONVENTIONS, _Coordinate.Convention));
    ncfile.addAttribute(null, new Attribute("format", volScan.getDataFormat()));
    ncfile.addAttribute(null, new Attribute(CF.FEATURE_TYPE, FeatureType.RADIAL.toString()));
    // Date d = Cinrad2Record.getDate(volScan.getTitleJulianDays(), volScan.getTitleMsecs());
    // ncfile.addAttribute(null, new Attribute("base_date", formatter.toDateOnlyString(d)));

    ncfile.addAttribute(null,
        new Attribute("time_coverage_start", formatter.toDateTimeStringISO(volScan.getStartDate())));// .toDateTimeStringISO(d)));
    ncfile.addAttribute(null, new Attribute("time_coverage_end", formatter.toDateTimeStringISO(volScan.getEndDate())));

    ncfile.addAttribute(null,
        new Attribute(CDM.HISTORY, "Direct read of Nexrad Level 2 file into NetCDF-Java 2.2 API"));
    ncfile.addAttribute(null, new Attribute("DataType", "Radial"));

    ncfile.addAttribute(null,
        new Attribute("Title",
            "Nexrad Level 2 Station " + volScan.getStationId() + " from "
                + formatter.toDateTimeStringISO(volScan.getStartDate()) + " to "
                + formatter.toDateTimeStringISO(volScan.getEndDate())));

    ncfile.addAttribute(null, new Attribute("Summary", "Weather Surveillance Radar-1988 Doppler (WSR-88D) "
        + "Level II data are the three meteorological base data quantities: reflectivity, mean radial velocity, and "
        + "spectrum width."));

    ncfile.addAttribute(null, new Attribute("keywords",
        "WSR-88D; NEXRAD; Radar Level II; reflectivity; mean radial velocity; spectrum width"));

    ncfile.addAttribute(null,
        new Attribute("VolumeCoveragePatternName", Cinrad2Record.getVolumeCoveragePatternName(volScan.getVCP())));
    ncfile.addAttribute(null, new Attribute("VolumeCoveragePattern", volScan.getVCP()));
    ncfile.addAttribute(null,
        new Attribute("HorizonatalBeamWidthInDegrees", (double) Cinrad2Record.HORIZONTAL_BEAM_WIDTH));

    ncfile.finish();
  }

  public Variable makeVariable(NetcdfFile ncfile, int datatype, String shortName, String longName, String abbrev,
      List groups) {
    int nscans = groups.size();

    if (nscans == 0) {
      throw new IllegalStateException("No data for " + shortName);
    }

    // get representative record
    List firstGroup = (List) groups.get(0);
    Cinrad2Record firstRecord = (Cinrad2Record) firstGroup.get(0);
    int ngates = firstRecord.getGateCount(datatype);

    String scanDimName = "scan" + abbrev;
    String gateDimName = "gate" + abbrev;
    Dimension scanDim = new Dimension(scanDimName, nscans);
    Dimension gateDim = new Dimension(gateDimName, ngates);
    ncfile.addDimension(null, scanDim);
    ncfile.addDimension(null, gateDim);

    ArrayList<Dimension> dims = new ArrayList<>();
    dims.add(scanDim);
    dims.add(radialDim);
    dims.add(gateDim);

    Variable v = new Variable(ncfile, null, null, shortName);
    if (isCC)
      v.setDataType(DataType.SHORT);
    else
      v.setDataType(DataType.UBYTE);
    v.setDimensions(dims);
    ncfile.addVariable(null, v);

    v.addAttribute(new Attribute(CDM.UNITS, Cinrad2Record.getDatatypeUnits(datatype)));
    v.addAttribute(new Attribute(CDM.LONG_NAME, longName));


    byte[] b = new byte[2];
    b[0] = Cinrad2Record.MISSING_DATA;
    b[1] = Cinrad2Record.BELOW_THRESHOLD;
    Array missingArray = Array.factory(DataType.BYTE, new int[] {2}, b);
    if (isCC)
      v.addAttribute(new Attribute(CDM.MISSING_VALUE, (short) -32768));
    else if (isCC20 && shortName.contains("RadialVelocity"))
      v.addAttribute(new Attribute(CDM.MISSING_VALUE, -128));
    else
      v.addAttribute(new Attribute(CDM.MISSING_VALUE, missingArray));
    // v.addAttribute( new Attribute(CDM.MISSING_VALUE, missingArray));
    v.addAttribute(new Attribute("signal_below_threshold", Cinrad2Record.BELOW_THRESHOLD));
    v.addAttribute(new Attribute(CDM.SCALE_FACTOR, Cinrad2Record.getDatatypeScaleFactor(datatype)));
    v.addAttribute(new Attribute(CDM.ADD_OFFSET, Cinrad2Record.getDatatypeAddOffset(datatype)));
    // if (!isCC)
    // v.addAttribute( new Attribute(CDM.UNSIGNED, "true"));

    ArrayList<Dimension> dim2 = new ArrayList<>();
    dim2.add(scanDim);
    dim2.add(radialDim);

    // add time coordinate variable
    String timeCoordName = "time" + abbrev;
    Variable timeVar = new Variable(ncfile, null, null, timeCoordName);
    timeVar.setDataType(DataType.INT);
    timeVar.setDimensions(dim2);
    ncfile.addVariable(null, timeVar);

    // int julianDays = volScan.getTitleJulianDays();
    // Date d = Cinrad2Record.getDate( julianDays, 0);
    // Date d = Cinrad2Record.getDate(volScan.getTitleJulianDays(), volScan.getTitleMsecs());
    Date d = volScan.getStartDate();
    String units = "msecs since " + formatter.toDateTimeStringISO(d);

    timeVar.addAttribute(new Attribute(CDM.LONG_NAME, "time since base date"));
    timeVar.addAttribute(new Attribute(CDM.UNITS, units));
    timeVar.addAttribute(new Attribute(CDM.MISSING_VALUE, MISSING_INT));
    timeVar.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.Time.toString()));

    // add elevation coordinate variable
    String elevCoordName = "elevation" + abbrev;
    Variable elevVar = new Variable(ncfile, null, null, elevCoordName);
    elevVar.setDataType(DataType.FLOAT);
    elevVar.setDimensions(dim2);
    ncfile.addVariable(null, elevVar);

    elevVar.addAttribute(new Attribute(CDM.UNITS, "degrees"));
    elevVar.addAttribute(
        new Attribute(CDM.LONG_NAME, "elevation angle in degres: 0 = parallel to pedestal base, 90 = perpendicular"));
    elevVar.addAttribute(new Attribute(CDM.MISSING_VALUE, MISSING_FLOAT));
    elevVar.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.RadialElevation.toString()));

    // add azimuth coordinate variable
    String aziCoordName = "azimuth" + abbrev;
    Variable aziVar = new Variable(ncfile, null, null, aziCoordName);
    aziVar.setDataType(DataType.FLOAT);
    aziVar.setDimensions(dim2);
    ncfile.addVariable(null, aziVar);

    aziVar.addAttribute(new Attribute(CDM.UNITS, "degrees"));
    aziVar.addAttribute(new Attribute(CDM.LONG_NAME, "azimuth angle in degrees: 0 = true north, 90 = east"));
    aziVar.addAttribute(new Attribute(CDM.MISSING_VALUE, MISSING_FLOAT));
    aziVar.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.RadialAzimuth.toString()));

    // add gate coordinate variable
    String gateCoordName = "distance" + abbrev;
    Variable gateVar = new Variable(ncfile, null, null, gateCoordName);
    gateVar.setDataType(DataType.FLOAT);
    gateVar.setDimensions(gateDimName);
    Array data = Array.makeArray(DataType.FLOAT, ngates, (double) firstRecord.getGateStart(datatype),
        (double) firstRecord.getGateSize(datatype));
    gateVar.setCachedData(data, false);
    ncfile.addVariable(null, gateVar);
    radarRadius = firstRecord.getGateStart(datatype) + ngates * firstRecord.getGateSize(datatype);

    gateVar.addAttribute(new Attribute(CDM.UNITS, "m"));
    gateVar.addAttribute(new Attribute(CDM.LONG_NAME, "radial distance to start of gate"));
    gateVar.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.RadialDistance.toString()));

    // add number of radials variable
    String nradialsName = "numRadials" + abbrev;
    Variable nradialsVar = new Variable(ncfile, null, null, nradialsName);
    nradialsVar.setDataType(DataType.INT);
    nradialsVar.setDimensions(scanDim.getShortName());
    nradialsVar.addAttribute(new Attribute(CDM.LONG_NAME, "number of valid radials in this scan"));
    ncfile.addVariable(null, nradialsVar);

    // add number of gates variable
    String ngateName = "numGates" + abbrev;
    Variable ngateVar = new Variable(ncfile, null, null, ngateName);
    ngateVar.setDataType(DataType.INT);
    ngateVar.setDimensions(scanDim.getShortName());
    ngateVar.addAttribute(new Attribute(CDM.LONG_NAME, "number of valid gates in this scan"));
    ncfile.addVariable(null, ngateVar);

    makeCoordinateDataWithMissing(datatype, timeVar, elevVar, aziVar, nradialsVar, ngateVar, groups);

    // back to the data variable
    String coordinates = timeCoordName + " " + elevCoordName + " " + aziCoordName + " " + gateCoordName;
    v.addAttribute(new Attribute(_Coordinate.Axes, coordinates));

    // make the record map
    int nradials = radialDim.getLength();
    Cinrad2Record[][] map = new Cinrad2Record[nscans][nradials];
    for (int i = 0; i < groups.size(); i++) {
      Cinrad2Record[] mapScan = map[i];
      List group = (List) groups.get(i);
      for (int j = 0; j < nradials; j++) {
        Cinrad2Record r = (Cinrad2Record) group.get(j);
        int radial = r.radial_num - 1;
        mapScan[radial] = r;
      }
    }

    Vgroup vg = new Vgroup(datatype, map);
    v.setSPobject(vg);

    return v;
  }

  private void makeVariableNoCoords(NetcdfFile ncfile, int datatype, String shortName, String longName, Variable from) {

    Variable v = new Variable(ncfile, null, null, shortName);
    if (isCC)
      v.setDataType(DataType.SHORT);
    else
      v.setDataType(DataType.UBYTE);
    v.setDimensions(from.getDimensions());
    ncfile.addVariable(null, v);

    v.addAttribute(new Attribute(CDM.UNITS, Cinrad2Record.getDatatypeUnits(datatype)));
    v.addAttribute(new Attribute(CDM.LONG_NAME, longName));

    byte[] b = new byte[2];
    b[0] = Cinrad2Record.MISSING_DATA;
    b[1] = Cinrad2Record.BELOW_THRESHOLD;
    Array missingArray = Array.factory(DataType.BYTE, new int[] {2}, b);
    if (isCC)
      v.addAttribute(new Attribute(CDM.MISSING_VALUE, (short) -32768));
    else
      v.addAttribute(new Attribute(CDM.MISSING_VALUE, missingArray));
    v.addAttribute(new Attribute("signal_below_threshold", Cinrad2Record.BELOW_THRESHOLD));
    v.addAttribute(new Attribute(CDM.SCALE_FACTOR, Cinrad2Record.getDatatypeScaleFactor(datatype)));
    v.addAttribute(new Attribute(CDM.ADD_OFFSET, Cinrad2Record.getDatatypeAddOffset(datatype)));
    // v.addAttribute( new Attribute(CDM.UNSIGNED, "true"));

    Attribute fromAtt = from.findAttribute(_Coordinate.Axes);
    v.addAttribute(new Attribute(_Coordinate.Axes, fromAtt));

    Vgroup vgFrom = (Vgroup) from.getSPobject();
    Vgroup vg = new Vgroup(datatype, vgFrom.map);
    v.setSPobject(vg);
  }

  private void makeCoordinateData(int datatype, Variable time, Variable elev, Variable azi, Variable nradialsVar,
      Variable ngatesVar, List<Group> groups) {

    Array timeData = Array.factory(time.getDataType(), time.getShape());
    IndexIterator timeDataIter = timeData.getIndexIterator();

    Array elevData = Array.factory(elev.getDataType(), elev.getShape());
    IndexIterator elevDataIter = elevData.getIndexIterator();

    Array aziData = Array.factory(azi.getDataType(), azi.getShape());
    IndexIterator aziDataIter = aziData.getIndexIterator();

    Array nradialsData = Array.factory(nradialsVar.getDataType(), nradialsVar.getShape());
    IndexIterator nradialsIter = nradialsData.getIndexIterator();

    Array ngatesData = Array.factory(ngatesVar.getDataType(), ngatesVar.getShape());
    IndexIterator ngatesIter = ngatesData.getIndexIterator();

    int last_msecs = Integer.MIN_VALUE;
    int nscans = groups.size();
    int maxRadials = volScan.getMaxRadials();
    for (Object group : groups) {
      List scanGroup = (List) group;
      int nradials = scanGroup.size();

      boolean needFirst = true;
      for (Object o : scanGroup) {
        Cinrad2Record r = (Cinrad2Record) o;
        if (needFirst) {
          ngatesIter.setIntNext(r.getGateCount(datatype));
          needFirst = false;
        }

        timeDataIter.setIntNext(r.data_msecs);
        elevDataIter.setFloatNext(r.getElevation());
        aziDataIter.setFloatNext(r.getAzimuth());

        if (r.data_msecs < last_msecs) {
          logger.warn("makeCoordinateData time out of order " + r.data_msecs);
        }
        last_msecs = r.data_msecs;
      }

      for (int j = nradials; j < maxRadials; j++) {
        timeDataIter.setIntNext(MISSING_INT);
        elevDataIter.setFloatNext(MISSING_FLOAT);
        aziDataIter.setFloatNext(MISSING_FLOAT);
      }

      nradialsIter.setIntNext(nradials);
    }

    time.setCachedData(timeData, false);
    elev.setCachedData(elevData, false);
    azi.setCachedData(aziData, false);
    nradialsVar.setCachedData(nradialsData, false);
    ngatesVar.setCachedData(ngatesData, false);
  }

  private void makeCoordinateDataWithMissing(int datatype, Variable time, Variable elev, Variable azi,
      Variable nradialsVar, Variable ngatesVar, List groups) {

    Array timeData = Array.factory(time.getDataType(), time.getShape());
    Index timeIndex = timeData.getIndex();

    Array elevData = Array.factory(elev.getDataType(), elev.getShape());
    Index elevIndex = elevData.getIndex();

    Array aziData = Array.factory(azi.getDataType(), azi.getShape());
    Index aziIndex = aziData.getIndex();

    Array nradialsData = Array.factory(nradialsVar.getDataType(), nradialsVar.getShape());
    IndexIterator nradialsIter = nradialsData.getIndexIterator();

    Array ngatesData = Array.factory(ngatesVar.getDataType(), ngatesVar.getShape());
    IndexIterator ngatesIter = ngatesData.getIndexIterator();

    // first fill with missing data
    IndexIterator ii = timeData.getIndexIterator();
    while (ii.hasNext())
      ii.setIntNext(MISSING_INT);

    ii = elevData.getIndexIterator();
    while (ii.hasNext())
      ii.setFloatNext(MISSING_FLOAT);

    ii = aziData.getIndexIterator();
    while (ii.hasNext())
      ii.setFloatNext(MISSING_FLOAT);

    // now set the coordinate variables from the Cinrad2Record radial
    int last_msecs = Integer.MIN_VALUE;
    int nscans = groups.size();
    try {
      for (int scan = 0; scan < nscans; scan++) {
        List scanGroup = (List) groups.get(scan);
        int nradials = scanGroup.size();

        boolean needFirst = true;
        for (int j = 0; j < nradials; j++) {
          Cinrad2Record r = (Cinrad2Record) scanGroup.get(j);
          if (needFirst) {
            ngatesIter.setIntNext(r.getGateCount(datatype));
            needFirst = false;
          }
          if (j < radialDim.getLength()) {
            int radial = r.radial_num - 1;
            timeData.setInt(timeIndex.set(scan, radial), r.data_msecs);
            elevData.setFloat(elevIndex.set(scan, radial), r.getElevation());
            aziData.setFloat(aziIndex.set(scan, radial), r.getAzimuth());

            if (r.data_msecs < last_msecs)
              logger.warn("makeCoordinateData time out of order " + r.data_msecs);
            last_msecs = r.data_msecs;
          }
        }

        nradialsIter.setIntNext(nradials);
      }
    } catch (java.lang.ArrayIndexOutOfBoundsException ae) {
      logger.debug("Cinrad2IOSP.uncompress ", ae);
    }
    time.setCachedData(timeData, false);
    elev.setCachedData(elevData, false);
    azi.setCachedData(aziData, false);
    nradialsVar.setCachedData(nradialsData, false);
    ngatesVar.setCachedData(ngatesData, false);
  }

  public Array readData(Variable v2, Section section) throws IOException {
    Vgroup vgroup = (Vgroup) v2.getSPobject();

    Range scanRange = section.getRange(0);
    Range radialRange = section.getRange(1);
    Range gateRange = section.getRange(2);

    Array data = Array.factory(v2.getDataType(), section.getShape());
    IndexIterator ii = data.getIndexIterator();

    for (int scanIdx : scanRange) {
      Cinrad2Record[] mapScan = vgroup.map[scanIdx];
      readOneScan(mapScan, radialRange, gateRange, vgroup.datatype, ii);
    }

    return data;
  }

  private void readOneScan(Cinrad2Record[] mapScan, Range radialRange, Range gateRange, int datatype, IndexIterator ii)
      throws IOException {
    for (int radialIdx : radialRange) {
      Cinrad2Record r = mapScan[radialIdx];
      readOneRadial(r, datatype, gateRange, ii);
    }
  }

  private void readOneRadial(Cinrad2Record r, int datatype, Range gateRange, IndexIterator ii) throws IOException {
    if (r == null) {
      for (int i = 0; i < gateRange.length(); i++) {
        if (isCC)
          ii.setShortNext((short) -32768);
        else
          ii.setByteNext(Cinrad2Record.MISSING_DATA);
      }
      return;
    }

    if (isSC)
      r.readData0(this.raf, datatype, gateRange, ii);
    else if (isCC)
      r.readData1(this.raf, datatype, gateRange, ii);
    else
      r.readData(this.raf, datatype, gateRange, ii);
  }

  private static class Vgroup {
    Cinrad2Record[][] map;
    int datatype;

    Vgroup(int datatype, Cinrad2Record[][] map) {
      this.datatype = datatype;
      this.map = map;
    }
  }

}
