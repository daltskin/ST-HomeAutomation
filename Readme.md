# Home Automation with SmartThings, Samsung Family Hub, Nest and other devices with the Spotify API

## Background
This project started because I wanted to play a music track whenever the door on the fridge freezer was open or closed. After getting a Samsung Smart Fridge I wanted to investigate the world of smart home automation and see what was possible.  I ended up doing this: [Smart Fridge automation](https://twitter.com/daltskin/status/1086995132816207872) and this: [Nest thermostat automation](https://twitter.com/daltskin/status/1088144892126265344) 

## Overview
This project consists of a SmartThings SmartApp which sends sensor events to an Azure Function.  Within the payload to the Azure Function the sensor type, state and spotify track details are passed.  These are used so that Spotify can then be controlled by it's API - playing back music from any registered Spotify device.  
The Azure Function invokes Spotify to automatically play the specified music track at a given position.  Depending on the sensor type, Spotify's settings will be re-instated either once the Azure Function is invoked again with the `closed` state of the sensor, or once a specified duration has passed - so as not to disturb any music that is already playing on a device.

## Customisation
You'll most likely need to customise this to suit your own needs and devices you have that you want to monitor.  As per above, I'm triggering from specific devices within my own SmartThings home network.  Depending on what sensors and states you want to monitor, you'll need to tweak the code to react on their statuses.  The code should be straight forward enough as a starting point to make modifications.

# Get Started

## Prerequisites
This project requires the following:

* Spotify Premium Account
* Spotify Developer App registration
* Azure Subscription
* SmartThings Hub/Family Hub
* SmartThings device(s)

## Components
This project contains code to reproduce the following components:

* Azure Function - an HTTP trigger that automates the Spotify API
* Azure Keyvault - to store the Spotify OAuth refresh key
* SmartThings SmartApp - a SmartApp that sends events from SmartThings graph to the Azure Function
* SmartThings Device Handler - a device handler that can be used to trigger the the Azure Function and associated to a virtual device
* Stringify Flow - to invoke the Azure Function from Nest activities

# Setup

## Spotify
I've created a single Spotify playlist for my all my home automation events imaginatively called `Home Automation`.  Here I've added each track I want to play for any sensor around the home.
![Spotify playlist](https://github.com/daltskin/HomeAutomation/blob/master/Documentation/spotifyplaylist.png)

## Spotify Developer Dashboard
Since we're automating Spotify via it's API, we need to create an application within the Spotify Developer Dashboard - this will provision you both a `Client ID` and a `Client Secret`.  You'll need both these values for the next stage.

![Spotify client details](https://github.com/daltskin/HomeAutomation/blob/master/Documentation/spotifydeveloperportal.png)

## Azure Function - AFSpotifyTrigger
The bulk of the work is done within this Azure Function, which is invoked from an HTTP call from the SmartThings SmartApp you will deploy later.  The 2.0 Azure Function automates Spotify using [Jonas Dellinger's SpotifyAPI-NET library](https://github.com/JohnnyCrazy/SpotifyAPI-NET) to do varies tasks such as interogating the user's Spotify devices and current playback track.  The Spotify API requires an OAuth access token to invoke it, an access token only lasts 60 minutes.  This Azure Function therefore uses a refresh token stored within KeyVault to generate a new access token - this in turn gets cached.

You will need to publish the Azure Function into your Azure subscription, then use the published URL in the next steps below.

### Application Settings
There are a number of configuration settings:

* AccessKey - secret only you know to invoke the Azure Function (you'll likely want to encrypt this)
* ClientID - the Spotify Client ID from above
* ClientSecret - the Spotify Client Secret from above
* RefreshToken - Used to get a valid Spotify OAuth access token - stored in Azure Key Vault

You'll need to obtain a valid Oauth refresh token from Spotify.  I used Postman to generate both an access token and a refresh token.  The refresh token can be stored within Azure Key Vault as a secret.  Once this is done you can take the Azure Key Vault SecretUri and add it to the Azure Function application settings eg:

You'll then need to use a system identity within your Azure Function to give it access to retrieve the refresh token from Key Vault.  Within your Azure Function, select `Platform features` - Identity - select system assigned to `On`.  This will create a principal which you can configure access to within Key Vault.

Then go back into your Azure Key Vault, select `Access Policies` - add new - select the principal from above.  Select `Secret permissions` - Get.  Finally, set the authorised application to the Azure Function and `Save` (don't forget this stage!).

At this point you should have a working Azure Function which you can hit in Postman to invoke Spotify, by passing in the json data required eg:

https://yournewfunctionname.azurewebsites.net/api/SpotifyTrigger?code=xyz1234

### HTTP Post Header
* Content-Type:application/json
* Authorization: {AccessKey} 

### HTTP Post Body

``` json
{
    "Mode": "Home",
    "Sensor": {
        "Name": "pir",
        "State": "active"
    },
    "SpotifySettings": {
        "PlaylistURI": "{PLAYLISTURL}",
        "PlaybackDevice": "My Speaker",
        "PlaybackTrackNumber": 0,
        "PlaybackVolume": 50,
        "PlaybackDuration": 10,
        "PlaybackTrackSeekPosition": 0
    }
```

# SmartThings
Any sensor that's connected to SmartThings can be used to invoke the Azure Function to play music.  Here, I'm just using the following:

* Motions
* Contacts

## SmartThings SmartApp - AFSpotifyTrigger
Use the AFSpotifyTrigger.groovy code to deploy a My SmartApps within the [SmartThings Groovy IDE](https://graph-eu01-euwest1.api.smartthings.com/)

* Select My SmartApps
* New SmartApp
* From Code
* Paste the contents of AFSpotifyTrigger.groovy
* Create
* Select AFSpotifyTrigger SmartApp
* Expand Settings
* Add the Azure Function URL endpoint (deployed above) to the `FunctionUri` value field
* Add your Azure Function secret to the `Secret` value field

![SmartApp](https://github.com/daltskin/HomeAutomation/blob/master/Documentation/smartapp.png)

Now on your mobile device you need to install the new AFSpotifyTrigger application into your Automations.  Once you do this you can configure which sensors that will trigger the Azure Function:

### Alarm Settings
Here you can set the Spotify device and music playback settings

* Alarm settings - the Spotify device the alarm (music) will be played from
* Alarm Track - the track position in the Spotify Playlist (zero based)
* Alarm Volume - volume of the target spotify device
* Alarm Duration - the duration to play the music
* Alarm Seek Position - the starting position to play the track back from

### Fridge Settings
Here you can set the Spotify device and music playback settings

* Fridge settings - the Spotify device the fridge music will be played from
* Fridge Track - the track position in the Spotify Playlist (zero based)
* Fridge Volume - volume of the target spotify device
* Fridge Duration - the duration to play the music
* Fridge Seek Position - the starting position to play the track back from

### Freezer Settings
Here you can set the Spotify device and music playback settings

* Freezer settings - the Spotify device the fridge music will be played from
* Freezer Track - the track position in the Spotify Playlist (zero based)
* Freezer Volume - volume of the target spotify device
* Freezer Duration - the duration to play the music
* Freezer Seek Position - the starting position to play the track back from

## SmartThings Device Hanlder - AFSpotifyTrigger
I've also included a device handler, so that you can setup a virtual button.  Using this you can then create a virtual device button - which whenever you switch on will play any music you like :)

# Nest
Currently SmartThings doesn't work with Nest.  Therefore, I needed another way to trigger the Azure Function based on the Nest device.  There are a few ways to do this, I looked at [Stringify](https://www.stringify.com) and [IFFT](https://ifttt.com).  I used Stringify because, in my limited testing, notifications were received much quicker from Nest events compared to IFTTT.

## Stringify - Nest automation
You'll need to install Stringify on your mobile device, create an account.  Then add Nest and Connect Maker as a `Thing`.  You then create a Flow between the Nest Home `Trigger` and the Connect Maker `Action` to Send an HTTP Get.