/**
 *  Azure Function Spotify Device Handler
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
 
preferences {
	section("Azure Function Settings"){
		input "functionUri", "text", title: "Azure Function endpoint", required: true
		input "functionSecret", "text", title: "Azure Function secret", required: true
	}
	section("Spotify Settings"){
		input "playbackDevice", "text", title: "Spotify Device", required: true
        input "playlistUri", "text", title: "Playlist Uri", required: true
 		input "sensorType", "text", title: "Sensor Type", required: true
		input "sensorName", "text", title: "Sensor Name", required: true
        input "volume", "number", title: "Volume", required: true
		input "duration", "number", title: "Duration", required: false
        input "track", "number", title: "Track", required: true
        input "seek", "number", title: "Seek", required: true
	}
}

metadata {
	definition (
    name: "AFSpotifyDeviceHandler",
    namespace: "daltskin",
    author: "J Dalton",
    description: "SmartThings Azure Function Spotify Device Handler",
    category: "My Apps",
    iconUrl: "https://developer.spotify.com/assets/branding-guidelines/icon1@2x.png",
    iconX2Url: "https://developer.spotify.com/assets/branding-guidelines/icon1@2x.png",
    iconX3Url: "https://developer.spotify.com/assets/branding-guidelines/icon1@2x.png")  
    {
        capability "Switch"
	}

	// simulator metadata
	simulator {
	}

	// UI tile definitions
	tiles {
		standardTile("button", "device.switch", width: 2, height: 2, canChangeIcon: true) {
			state "off", label: 'Off', action: "switch.on", icon: "st.switches.switch.off", backgroundColor: "#ffffff", nextState: "on"
            state "on", label: 'On', action: "switch.off", icon: "st.switches.switch.on", backgroundColor: "#79b821", nextState: "off"
		}
	
		main "button"
			details (["button"])
	}
}

def parse(String description) {
    log.debug(description)
}

def on() {
	if (functionUri){
		processEvent("active")
	}
}

def off() {
	if (functionUri){
        processEvent("inactive")
	}
}

def processEvent(state) {
    
    // build payload
    def event = new JsonSlurper().parseText("{\"Mode\": \"$location.mode\",\"Sensor\": {\"Name\": \"$sensorName\",\"State\": \"$state\"},\"SpotifySettings\": {\"PlaylistURI\": \"$playlistUri\",\"PlaybackDevice\": \"$playbackDevice\",\"PlaybackTrackNumber\": \"$track\",\"PlaybackVolume\": \"$volume\",\"PlaybackDuration\": \"$duration\",\"PlaybackTrackSeekPosition\": \"$seek\"}}")

    def spotifyEvent = [
            uri: functionUri,
            headers: [
                "Authorization": functionSecret
            ],
            body: event
        ]
    log.debug("Sending: ${spotifyEvent}")
    
	try {
		httpPostJson(spotifyEvent) { resp ->
			log.debug "AF Spotify request ${resp.status}"
			if (resp.status != 200) {
				log.error "AF Spotify failure... ${resp.data}"
			}
		}
	} catch (e) {
		log.error "AF Spotify error: $e"
	}    
}
