/*
 * Copyright (c) 1998-2018 University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */

package ucar.nc2.iosp.bufr.writer;

import com.google.common.escape.Escaper;
import com.google.common.xml.XmlEscapers;
import ucar.nc2.constants.CDM;
import ucar.nc2.iosp.bufr.BufrIosp2;
import ucar.nc2.iosp.bufr.Message;
import ucar.nc2.*;
import ucar.nc2.util.Indent;
import ucar.nc2.dataset.VariableDS;
import ucar.nc2.dataset.SequenceDS;
import ucar.nc2.dataset.StructureDS;
import ucar.ma2.*;
import ucar.unidata.util.StringUtil2;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.stream.XMLOutputFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Formatter;

/**
 * Write BUFR to an ad-hoc XML format
 *
 * @author caron
 * @since Aug 9, 2008
 */
public class Bufr2Xml {
  private XMLStreamWriter staxWriter;
  private Indent indent;
  private Escaper escaper = XmlEscapers.xmlAttributeEscaper();

  public Bufr2Xml(Message message, NetcdfFile ncfile, OutputStream os, boolean skipMissing) throws IOException {
    indent = new Indent(2);
    indent.setIndentLevel(0);

    try {
      XMLOutputFactory fac = XMLOutputFactory.newInstance();
      staxWriter = fac.createXMLStreamWriter(os, CDM.UTF8);

      staxWriter.writeStartDocument(CDM.UTF8, "1.0");
      // staxWriter.writeCharacters("\n");
      // staxWriter.writeStartElement("bufrMessage");

      writeMessage(message, ncfile);

      staxWriter.writeCharacters("\n");
      staxWriter.writeEndDocument();
      staxWriter.flush();

    } catch (XMLStreamException e) {
      throw new IOException(e.getMessage());
    }
  }

  void writeMessage(Message message, NetcdfFile ncfile) {

    try {
      staxWriter.writeCharacters("\n");
      staxWriter.writeCharacters(indent.toString());
      staxWriter.writeStartElement("bufrMessage");
      staxWriter.writeAttribute("nobs", Integer.toString(message.getNumberDatasets()));
      indent.incr();

      staxWriter.writeCharacters("\n");
      staxWriter.writeCharacters(indent.toString());

      staxWriter.writeCharacters("\n");
      staxWriter.writeCharacters(indent.toString());
      staxWriter.writeStartElement("edition");
      staxWriter.writeCharacters(Integer.toString(message.is.getBufrEdition()));
      staxWriter.writeEndElement();

      String header = message.getHeader().trim();
      if (!header.isEmpty()) {
        staxWriter.writeCharacters("\n");
        staxWriter.writeCharacters(indent.toString());
        staxWriter.writeStartElement("header");
        staxWriter.writeCharacters(header);
        staxWriter.writeEndElement();
      }

      staxWriter.writeCharacters("\n");
      staxWriter.writeCharacters(indent.toString());
      staxWriter.writeStartElement("tableVersion");
      staxWriter.writeCharacters(message.getLookup().getTableName());
      staxWriter.writeEndElement();

      staxWriter.writeStartElement("center");
      staxWriter.writeCharacters(message.getLookup().getCenterName());
      staxWriter.writeEndElement();

      staxWriter.writeCharacters("\n");
      staxWriter.writeCharacters(indent.toString());
      staxWriter.writeStartElement("category");
      staxWriter.writeCharacters(message.getLookup().getCategoryFullName());
      staxWriter.writeEndElement();

      SequenceDS obs = (SequenceDS) ncfile.findVariable(BufrIosp2.obsRecord);
      StructureDataIterator sdataIter = obs.getStructureIterator(-1);

      writeSequence(obs, sdataIter);

      // ending
      indent.decr();
      staxWriter.writeCharacters("\n");
      staxWriter.writeCharacters(indent.toString());
      staxWriter.writeEndElement();

    } catch (IOException | XMLStreamException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  /*
   * void writeStructureArray(StructureDS s, ArrayStructure data, Indent indent) throws IOException, XMLStreamException
   * {
   * StructureDataIterator sdataIter = data.getStructureDataIterator();
   * while (sdataIter.hasNext()) {
   * StructureData sdata = sdataIter.next();
   * staxWriter.writeCharacters("\n");
   * staxWriter.writeCharacters(indent.toString());
   * staxWriter.writeStartElement(s.getShortName());
   * 
   * indent.incr();
   * for (StructureMembers.Member m : sdata.getMembers()) {
   * Variable v = s.findVariable(m.getName());
   * 
   * if (m.getDataType().isString() || m.getDataType().isNumeric()) {
   * writeVariable((VariableDS) v, sdata.getArray(m), indent);
   * 
   * } else if (m.getDataType() == DataType.STRUCTURE) {
   * writeStructureArray((StructureDS) v, (ArrayStructure) sdata.getArray(m), indent);
   * 
   * } else if (m.getDataType() == DataType.SEQUENCE) {
   * writeSequence((SequenceDS) v, (ArraySequence) sdata.getArray(m), indent);
   * }
   * 
   * if (m.getDataType().isString() || m.getDataType().isNumeric()) {
   * writeVariable((VariableDS) v, sdata.getArray(m), indent);
   * }
   * }
   * indent.decr();
   * 
   * staxWriter.writeCharacters("\n");
   * staxWriter.writeCharacters(indent.toString());
   * staxWriter.writeEndElement();
   * }
   * }
   */

  // iterate through the observations

  private void writeSequence(StructureDS s, StructureDataIterator sdataIter) throws IOException, XMLStreamException {

    int count = 0;
    try {
      while (sdataIter.hasNext()) {
        // out.format("%sSequence %s count=%d%n", indent, s.getShortName(), count++);
        StructureData sdata = sdataIter.next();

        staxWriter.writeCharacters("\n");
        staxWriter.writeCharacters(indent.toString());
        staxWriter.writeStartElement("struct");
        staxWriter.writeAttribute("name", escaper.escape(s.getShortName()));
        staxWriter.writeAttribute("count", Integer.toString(count++));

        for (StructureMembers.Member m : sdata.getMembers()) {
          Variable v = s.findVariable(m.getName());
          indent.incr();

          if (m.getDataType().isString() || m.getDataType().isNumeric()) {
            writeVariable((VariableDS) v, sdata.getArray(m));

          } else if (m.getDataType() == DataType.STRUCTURE) {
            StructureDS sds = (StructureDS) v;
            ArrayStructure data = (ArrayStructure) sdata.getArray(m);
            writeSequence(sds, data.getStructureDataIterator());

          } else if (m.getDataType() == DataType.SEQUENCE) {
            SequenceDS sds = (SequenceDS) v;
            ArraySequence data = (ArraySequence) sdata.getArray(m);
            writeSequence(sds, data.getStructureDataIterator());
          }
          indent.decr();
        }

        staxWriter.writeCharacters("\n");
        staxWriter.writeCharacters(indent.toString());
        staxWriter.writeEndElement();
      }
    } finally {
      sdataIter.close();
    }
  }

  void writeVariable(VariableDS v, Array mdata) throws XMLStreamException {
    staxWriter.writeCharacters("\n");
    staxWriter.writeCharacters(indent.toString());

    // complete option
    staxWriter.writeStartElement("data");
    String name = v.getShortName();
    staxWriter.writeAttribute("name", escaper.escape(name));

    String units = v.getUnitsString();
    if ((units != null) && !units.equals(name) && !units.startsWith("Code"))
      staxWriter.writeAttribute(CDM.UNITS, escaper.escape(v.getUnitsString()));

    Attribute att = v.findAttribute(BufrIosp2.fxyAttName);
    String desc = (att == null) ? "N/A" : att.getStringValue();
    staxWriter.writeAttribute("bufr", escaper.escape(desc));

    if (v.getDataType() == DataType.CHAR) {
      ArrayChar ac = (ArrayChar) mdata;
      staxWriter.writeCharacters(ac.getString()); // turn into a string

    } else {

      int count = 0;
      mdata.resetLocalIterator();
      while (mdata.hasNext()) {
        if (count > 0)
          staxWriter.writeCharacters(" ");
        count++;

        if (v.getDataType().isNumeric()) {
          double val = mdata.nextDouble();

          if (v.isMissing(val)) {
            staxWriter.writeCharacters("missing");

          } else if ((v.getDataType() == DataType.FLOAT) || (v.getDataType() == DataType.DOUBLE)) {
            writeFloat(v, val);

          } else { // numeric, not float
            staxWriter.writeCharacters(mdata.toString());
          }

        } else { // not numeric
          String s = StringUtil2.filter7bits(mdata.next().toString());
          staxWriter.writeCharacters(escaper.escape(s));
        }
      }
    }


    staxWriter.writeEndElement();
  }

  private void writeFloat(Variable v, double val) throws XMLStreamException {
    Attribute bitWidthAtt = v.findAttribute("BUFR:bitWidth");
    int sigDigits;
    if (bitWidthAtt == null)
      sigDigits = 7;
    else {
      int bitWidth = bitWidthAtt.getNumericValue().intValue();
      if (bitWidth < 30) {
        double sigDigitsD = Math.log10(2 << bitWidth);
        sigDigits = (int) (sigDigitsD + 1);
      } else {
        sigDigits = 7;
      }
    }

    Formatter stringFormatter = new Formatter();
    String format = "%." + sigDigits + "g";
    stringFormatter.format(format, val);
    staxWriter.writeCharacters(stringFormatter.toString());
  }
}


