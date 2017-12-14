package org.ehealth.dhis2SmsComp;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;

import org.ehealth.dhis2SmsComp.Models.SmsCode;
import org.ehealth.dhis2SmsComp.Models.SmsCommand;
import org.ehealth.dhis2SmsComp.Models.SmsSubmission;
import org.ehealth.dhis2SmsComp.Utils.BitInputStream;

import com.google.gson.Gson;

/**
 * A class used to decode an SMS which was previously encoded using SMSEncoder
 * @see SMSEncoder
 * @author jaspertimm
 *
 */
public class SmsDecoder {
	private ArrayList<SmsCommand> smsCmdList;

	/**
	 * Instantiate a decoder given a JSON of the SmsCommands in this
	 * DHIS2 environment
	 * @param smsCmds
	 */
	public SmsDecoder(Reader smsCmds) {
		Gson gson = new Gson();
		ArrayList<SmsCommand> smsList = new ArrayList<SmsCommand>();
		smsList.addAll(Arrays.asList(gson.fromJson(smsCmds, SmsCommand[].class)));
		this.smsCmdList = smsList;
	}
	
	/**
	 * A wrapper which simply prints the decoded SmsSubmission
	 * from decodeSMS
	 * @param smsBytes
	 * @return
	 * @throws IOException
	 */
	public String decode(byte[] smsBytes) throws IOException {
		SmsSubmission subm = decodeSMS(smsBytes);
		return subm.toString();
	}
	
	/**
	 * Decodes an SMS as a byte array previously encoded by SmsEncoder
	 * @param smsBytes
	 * @return
	 * @throws IOException
	 */
	public SmsSubmission decodeSMS(byte[] smsBytes) throws IOException
    {
		ByteArrayInputStream byteStream = new ByteArrayInputStream(smsBytes);
		BitInputStream bitStream = new BitInputStream(byteStream);
		SmsSubmission subm = new SmsSubmission();
		
		//Read command
		int cmdId = bitStream.read(SSPConst.CMD_BITLEN);
		subm.currentSmsCmd = smsCmdList.get(cmdId);
				
		//Read date of submission
		int submDate = bitStream.read(SSPConst.SUBM_DATE_BITLEN);
		if (submDate > 0) subm.submDate = submDate;
		
		//Read key length
		subm.keyLength = bitStream.read(SSPConst.KEYLEN_BITLEN);		
		
		//Read int length
		subm.intLength = bitStream.read(SSPConst.INTLEN_BITLEN);
		
		//Read remaining val
		subm.remainVal = decodeValue(bitStream, subm);
		
		//Read key value pairs
		ArrayList<SmsCode> remainCodes = new ArrayList<SmsCode>(subm.currentSmsCmd.smsCodes);
		subm.kvPairsMap = new HashMap<String, String>();
		while(true) {
			try {
				int keyId = bitStream.read(subm.keyLength);
				SmsCode smsCode = subm.currentSmsCmd.smsCodes.get(keyId);
				remainCodes.remove(smsCode);
				String val = decodeValue(bitStream, subm);
				if (val.isEmpty()) continue;
				subm.kvPairsMap.put(smsCode.smsCode, val);
				
			} catch (EOFException e) {
				break;				
			}
		}
		
		//Write out the remaining codes as the remain val if it's not blank
		if (!subm.remainVal.isEmpty()) {
			for (SmsCode code : remainCodes) {
				subm.kvPairsMap.put(code.smsCode, subm.remainVal);
			}
		}
		
		bitStream.close();
		return subm;
    }
	
	/**
	 * Detects the type of the next value from the bitStream and 
	 * returns it as a string
	 * @param bitStream
	 * @param subm
	 * @return
	 * @throws IOException
	 */
	private String decodeValue(BitInputStream bitStream, SmsSubmission subm) throws IOException {
		SimpleDateFormat sdf = new SimpleDateFormat(SSPConst.DATE_FORMAT);
		int typeId = bitStream.read(SSPConst.TYPE_BITLEN);
		SSPConst.SubmValueType valType = SSPConst.SubmValueType.values()[typeId] ;
		
		switch(valType) {
			case BLANK:
				return "";
				
			case BOOL:
				return bitStream.readBit() == 1 ? "true" : "false";
				
			case DATE:
				long epochSecs = bitStream.read(SSPConst.EPOCH_DATE_BITLEN);
				Date dateVal = new Date(epochSecs * 1000);
				return sdf.format(dateVal);
				
			case INT:
				int intVal = bitStream.read(subm.intLength);
				return Integer.toString(intVal);
				
			case STRING:
				String strVal = "";
				while (true) {
					int nextChar = bitStream.read(SSPConst.CHAR_BITLEN);
					if (nextChar == 0) break;
					strVal += (char) nextChar;
				}
				return strVal;
	
			default:
				throw new IOException("Unknown value type");
		}
		
	}
}