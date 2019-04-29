/**
 *  Azure Function Spotify Trigger
 *
 *  Copyright 2019 Jamie Dalton
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
 
import groovy.json.JsonSlurper
 
definition(
    name: "AFSpotifyTrigger",
    namespace: "daltskin",
    author: "J Dalton",
    description: "SmartThings Azure Function Spotify Trigger",
    category: "My Apps",
    iconUrl: "https://developer.spotify.com/assets/branding-guidelines/icon1@2x.png",
    iconX2Url: "https://developer.spotify.com/assets/branding-guidelines/icon1@2x.png",
    iconX3Url: "https://developer.spotify.com/assets/branding-guidelines/icon1@2x.png") 
    {
        appSetting "FunctionUri"
        appSetting "Secret"
    }

preferences {
    section("General settings...") {
        input "playlistUri", "text", title: "Spotify Playlist Uri", required: false, defaultValue: ""    
   	}  
    section("Which Sensors...", hideable: true, hidden: false) {
        input "motions", "capability.motionSensor", title: "Which Motions?", multiple: true, required: false
        input "contacts", "capability.contactSensor", title: "Which Contacts?", multiple: true, required: false
    }
   	section("Alarm settings...", hideable: true, hidden: false) {
        input "alarmDevice", "enum", title: "Device name", options: ["Invoke", "iPhone", "SAMSUNG FAMILY HUB"], required: false, defaultValue: ""
        input "alarmTrack", "number", title: "Track number", required: false, defaultValue: "5"
        input "alarmVolume", "number", title: "Volume", required: false, defaultValue: "10"
        input "alarmDuration", "number", title: "Duration", required: false, defaultValue: "60"
        input "alarmSeek", "number", title: "Seek Position", required: false, defaultValue: "3000"
    }    
    section("Fridge settings...", hideable: true, hidden: false) {
        input "fridgeDevice", "enum", title: "Device name", options: ["Invoke", "iPhone", "SAMSUNG FAMILY HUB"], required: false, defaultValue: ""
        input "fridgeTrack", "number", title: "Track number", required: false, defaultValue: "1"
        input "fridgeVolume", "number", title: "Volume", required: false, defaultValue: "30"
        input "fridgeDuration", "number", title: "Duration", required: false, defaultValue: "0"
        input "fridgeSeek", "number", title: "Seek Position", required: false, defaultValue: "19000"
    }    
    section("Freezer Settings...", hideable: true, hidden: false) {
        input "freezerDevice", "enum", title: "Device name", options: ["Invoke", "iPhone", "SAMSUNG FAMILY HUB"], required: false, defaultValue: ""
        input "freezerTrack", "number", title: "Track number", required: false, defaultValue: "0"
        input "freezerVolume", "number", title: "Volume", required: false, defaultValue: "30"
        input "freezerDuration", "number", title: "Duration", required: false, defaultValue: "0"
        input "freezerSeek", "number", title: "Seek Position", required: false, defaultValue: "0"
    }
}

def installed() {
	log.debug "Installed with settings: ${settings}"
	initialize()
    subscribeToEvents()
}

def updated() {
	log.debug "Updated with settings: ${settings}"
	unsubscribe()
    unschedule()
	subscribeToEvents()
}

def subscribeToEvents() {
    subscribe(contacts, "contact", contactHandler)
    subscribe(motions, "motion", motionHandler)
}

def contactHandler(evt) {
	log.debug "AFSpotifyTrigger: Contact event - device: ${evt.deviceId} name: ${evt.displayName} description text: ${evt.descriptionText} event value: ${evt.value} time: ${new Date().time}"
    
    switch (evt.displayName) {
        case ~/(?i).*cooler.*$/:
        	processEvent(evt.displayName, "contact", "cooler", evt.value, fridgeDuration, fridgeTrack, fridgeDevice, fridgeSeek, fridgeVolume)
        	break
        case ~/(?i).*freezer.*$/:
        	processEvent(evt.displayName, "contact", "freezer", evt.value, freezerDuration, freezerTrack, freezerDevice, freezerSeek, freezerVolume)
        	break
        default:
            break
    }
}

def motionHandler(evt) {
	log.debug "AFSpotifyTrigger: Motion event - device: ${evt.deviceId} name: ${evt.displayName} description text: ${evt.descriptionText} event value: ${evt.value} time: ${new Date().time}"

   	// All motion events are filtered on a time interval to avoid saturation
    // Rather than sending the inactive message which happens too soon after the initial active one
    def minSecondsBetweenEvents = 15
    if (evt.value == "active") {
        if (atomicState.motionLastrun == "") {
            log.debug "AFSpotifyTrigger: No previous motion run stored in state"
            atomicState.motionLastrun = new Date().time
            filterMotionEvent(evt)
        } 
        else if (secondsPast(atomicState.motionLastrun, minSecondsBetweenEvents)) {
            log.debug "AFSpotifyTrigger: new motion event"
            atomicState.motionLastrun = new Date().time
            filterMotionEvent(evt)
        }
        else {
        	log.debug "AFSpotifyTrigger: Motion last run time: ${atomicState.motionLastrun}"
            log.debug "AFSpotifyTrigger: Motion "
        }
	}
}

def filterMotionEvent(evt) {
    // Set whatever rules you want here around what state Smart Home Monitor is in, or ignore it completely
	def cur = location.currentState("alarmSystemStatus")?.value.toLowerCase()
            
    def fireEvent = false
    switch (cur) {
        case ['stay']:
        case ['away']:
        case ['night']:
            fireEvent = true
            break;
        case ['off']:
        case ['alarm_active']:
            fireEvent = false
            break;
    }
            
    if (fireEvent) {
		processEvent(evt.displayName, "motion", "pir", evt.value, alarmDuration, alarmTrack, alarmDevice, alarmSeek, alarmVolume)
    }
    else {
		log.debug "AFSpotifyTrigger: Motion event ignored as SHM: ${cur}"
    }
}

def processEvent(sensorId, sensorType, sensorName, value, duration, track, device, seek, volume) {
 	if (volume > 0) {

		// build payload
	    def event = new JsonSlurper().parseText("{\"Mode\": \"$location.mode\",\"Sensor\": {\"Name\": \"$sensorName\",\"State\": \"$value\"},\"SpotifySettings\": {\"PlaylistURI\": \"$playlistUri\",\"PlaybackDevice\": \"$device\",\"PlaybackTrackNumber\": \"$track\",\"PlaybackVolume\": \"$volume\",\"PlaybackDuration\": \"$duration\",\"PlaybackTrackSeekPosition\": \"$seek\"}}")
    
        def spotifyEvent = [
                uri: appSettings.FunctionUri,
                headers: [
                    "Authorization": appSettings.Secret
                ],
                body: event
            ]

        try {
            httpPostJson(spotifyEvent) { resp ->
                log.debug "AFSpotifyTrigger: AF Spotify request ${resp.status}"
                if (resp.status != 200) {
                    log.error "AFSpotifyTrigger: AF Spotify failure... ${resp.data}"
                }
            }
        } catch (e) {
            log.warning "AFSpotifyTrigger: AF Spotify error: $e"
        }    
    }
}

private Boolean secondsPast(timestamp, seconds) {
	if (!(timestamp instanceof Number)) {
		if (timestamp instanceof Date) {
			timestamp = timestamp.time
		} else if ((timestamp instanceof String) && timestamp.isNumber()) {
			timestamp = timestamp.toLong()
		} else {
			return true
		}
	}
	return (new Date().time - timestamp) > (seconds * 1000)
}
